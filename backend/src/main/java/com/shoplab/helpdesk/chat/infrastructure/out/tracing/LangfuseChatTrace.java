package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.config.TracingProperties;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.FailureStage;
import com.shoplab.helpdesk.chat.domain.model.enums.TurnResult;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Sends one trace per chat turn to Langfuse through OpenTelemetry (specs/LAB-64, contracts/trace-schema.md): a root
 * {@code chat-turn} span with a {@code retrieval}, a {@code generation} and one {@code tool:*} span per tool call.
 *
 * <p>Nothing here may reach the chat: every call to the SDK is guarded, a failure is logged without any content of the
 * conversation, and the spans are exported by the SDK on its own thread. Spans get their parent explicitly because the
 * OpenTelemetry context does not travel between the threads of a reactive chain.
 */
public class LangfuseChatTrace implements ChatTracePort, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LangfuseChatTrace.class);
    private static final String INSTRUMENTATION = "com.shoplab.helpdesk.chat";
    private static final String PROVIDER_PARAMETER = "provider";
    private static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    private final SdkTracerProvider provider;
    private final Tracer tracer;
    private final DerivedTraceIdGenerator ids;
    private final TracingProperties properties;
    private final ContentLimiter limiter;
    private final CostCalculator costs;
    private final Runnable afterClose;

    public LangfuseChatTrace(SdkTracerProvider provider, DerivedTraceIdGenerator ids, TracingProperties properties) {
        this(provider, ids, properties, () -> { });
    }

    /** {@code afterClose} runs once the pending spans were sent, to stop whatever else the pipeline started. */
    public LangfuseChatTrace(SdkTracerProvider provider, DerivedTraceIdGenerator ids, TracingProperties properties,
            Runnable afterClose) {
        this.afterClose = afterClose;
        this.provider = provider;
        this.tracer = provider.get(INSTRUMENTATION);
        this.ids = ids;
        this.properties = properties;
        this.limiter = new ContentLimiter(properties.maxContentCharacters());
        this.costs = new CostCalculator(properties.pricing());
        Context.root();
        this.tracer.spanBuilder(LangfuseAttributes.TURN_SPAN);
    }

    @Override
    public Flux<ChatEvent> traceTurn(StreamChatCommand command, Function<TurnTrace, Flux<ChatEvent>> turn) {
        return Flux.defer(() -> {
            Turn trace = open(command);
            if (trace == null) {
                return turn.apply(TurnTrace.NONE);
            }
            return turn.apply(trace)
                    .doOnNext(trace::onEvent)
                    .doOnError(trace::onError)
                    .doOnComplete(trace::onComplete)
                    .doOnCancel(trace::onCancel)
                    .contextWrite(context -> context.put(TracingConstants.TURN_TRACE_KEY, trace));
        });
    }

    /** Sends what is pending; used when the application stops and by the tests. */
    public void forceFlush() {
        provider.forceFlush().join(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            provider.shutdown().join(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            LOG.warn("The chat traces could not be sent while stopping: {}", e.getClass().getSimpleName());
        }
        try {
            afterClose.run();
        } catch (RuntimeException e) {
            LOG.warn("The tracing could not be stopped cleanly: {}", e.getClass().getSimpleName());
        }
    }

    private Turn open(StreamChatCommand command) {
        try {
            return new Turn(command);
        } catch (RuntimeException e) {
            warn("opening the trace", e);
            return null;
        }
    }

    private static void warn(String what, Throwable error) {
        LOG.warn("A chat trace could not record {}: {}", what, error.getClass().getSimpleName());
    }

    private static void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            warn(what, e);
        }
    }

    /** The trace of one turn. Its methods are called from the thread of the chain, and tools from other threads. */
    private final class Turn implements TurnTrace {

        private final StreamChatCommand command;
        private final Span root;
        private final Instant startedAt = Instant.now();
        private final StringBuilder answer = new StringBuilder();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean truncated = new AtomicBoolean();
        private volatile int historyTurns;
        private volatile int delivered;
        private volatile boolean fixedMessage;
        private volatile String failureCode;
        private volatile FailureStage failedStage;
        private volatile Generation generation;
        private volatile Instant firstTokenAt;

        private Turn(StreamChatCommand command) {
            this.command = command;
            ids.prepare(DerivedTraceIdGenerator.traceIdOf(command.requestId()));
            try {
                SpanBuilder builder = tracer.spanBuilder(LangfuseAttributes.TURN_SPAN).setNoParent();
                this.root = builder.startSpan();
            } finally {
                ids.clear();
            }
            String question = text(command.message());
            root.setAttribute(LangfuseAttributes.TRACE_NAME, LangfuseAttributes.TURN_SPAN);
            root.setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_SPAN);
            root.setAttribute(LangfuseAttributes.TRACE_TAGS, List.of("chat"));
            root.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, question);
            root.setAttribute(LangfuseAttributes.TRACE_INPUT, question);
            root.setAttribute(LangfuseAttributes.traceMetadata("request_id"), command.requestId());
            common(root);
        }

        /** Trace-level attributes that Langfuse wants on every span to filter reliably. */
        private void common(Span span) {
            span.setAttribute(LangfuseAttributes.ENVIRONMENT, properties.environment());
            if (command.conversationId() != null && !command.conversationId().isBlank()) {
                span.setAttribute(LangfuseAttributes.SESSION_ID, limiter.limit(command.conversationId()).text());
            }
        }

        private String text(String value) {
            ContentLimiter.Limited limited = limiter.limit(value);
            if (limited.truncated()) {
                truncated.set(true);
            }
            return limited.text();
        }

        private SpanBuilder child(String name, Span parent) {
            return tracer.spanBuilder(name).setParent(Context.root().with(parent));
        }

        // --- what the chain reports -------------------------------------------------------------------------------

        void onEvent(ChatEvent event) {
            safely("an event", () -> {
                if (event instanceof TokenEvent token) {
                    synchronized (answer) {
                        answer.append(token.content());
                    }
                } else if (event instanceof ErrorEvent error) {
                    failureCode = error.code();
                }
            });
        }

        void onComplete() {
            if (failureCode != null) {
                finish(TurnResult.ERROR);
            } else {
                finish(fixedMessage ? TurnResult.NO_CONTEXT : TurnResult.COMPLETED);
            }
        }

        void onError(Throwable error) {
            failureCode = error instanceof HelpdeskException typed ? typed.getResponseCode().getCode()
                    : INTERNAL_ERROR_CODE;
            finish(TurnResult.ERROR);
        }

        void onCancel() {
            finish(TurnResult.CANCELLED);
        }

        // --- what the use case and the model adapter report -------------------------------------------------------

        @Override
        public void historyTurns(int turns) {
            historyTurns = turns;
        }

        @Override
        public void retrieval(RetrievalTrace retrieval, Duration elapsed) {
            safely("the retrieval", () -> {
                delivered = retrieval.delivered().size();
                Span span = child(LangfuseAttributes.RETRIEVAL_SPAN, root)
                        .setStartTimestamp(Instant.now().minus(elapsed)).startSpan();
                common(span);
                span.setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_RETRIEVER);
                span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, text(retrieval.searchText()));
                span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, fragments(retrieval.delivered()));
                span.setAttribute(LangfuseAttributes.observationMetadata("candidates"),
                        String.valueOf(retrieval.candidates()));
                span.setAttribute(LangfuseAttributes.observationMetadata("delivered"),
                        String.valueOf(retrieval.delivered().size()));
                retrieval.delivered().stream().mapToDouble(ContextFragment::score).max()
                        .ifPresent(best -> span.setAttribute(LangfuseAttributes.observationMetadata("best_score"),
                                String.valueOf(best)));
                if (retrieval.failed()) {
                    failedStage = FailureStage.RETRIEVAL;
                    fail(span, "The search for context failed");
                }
                span.end();
            });
        }

        @Override
        public void fixedMessageAnswer() {
            fixedMessage = true;
        }

        @Override
        public GenerationScope startGeneration(String model, Map<String, Object> parameters,
                List<PromptMessage> messages) {
            try {
                Generation started = new Generation(model, parameters, messages);
                generation = started;
                return started;
            } catch (RuntimeException e) {
                warn("the generation", e);
                return GenerationScope.NONE;
            }
        }

        @Override
        public void toolCall(ToolCallTrace call, Duration elapsed) {
            safely("a tool call", () -> {
                Generation parent = generation;
                Span span = child(LangfuseAttributes.TOOL_SPAN_PREFIX + call.name(),
                        parent == null ? root : parent.span)
                        .setStartTimestamp(Instant.now().minus(elapsed)).startSpan();
                common(span);
                span.setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_TOOL);
                span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, text(call.arguments()));
                if (call.result() != null) {
                    span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, text(call.result()));
                }
                if (call.failed()) {
                    failedStage = FailureStage.TOOL;
                    fail(span, call.errorMessage() == null ? "The tool failed" : call.errorMessage());
                }
                span.end();
            });
        }

        // --- helpers ----------------------------------------------------------------------------------------------

        private String fragments(List<ContextFragment> fragments) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < fragments.size(); index++) {
                ContextFragment fragment = fragments.get(index);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("number", index + 1);
                item.put("source", fragment.source());
                item.put("title", fragment.title());
                item.put("section", fragment.section());
                item.put("position", fragment.position());
                item.put("score", fragment.score());
                item.put("text", text(fragment.text()));
                items.add(item);
            }
            return TraceJson.write(items);
        }

        private void fail(Span span, String message) {
            span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_ERROR);
            span.setAttribute(LangfuseAttributes.OBSERVATION_STATUS_MESSAGE, message);
            span.setStatus(StatusCode.ERROR, message);
        }

        private void finish(TurnResult result) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            safely("the end of the turn", () -> {
                Generation open = generation;
                if (open != null) {
                    if (result == TurnResult.ERROR && !open.ended.get()) {
                        open.fail(new IllegalStateException("The turn failed before the model finished"));
                    } else {
                        open.cancel(null);
                    }
                }
                String output;
                synchronized (answer) {
                    output = text(answer.toString());
                }
                root.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, output);
                root.setAttribute(LangfuseAttributes.TRACE_OUTPUT, output);
                root.setAttribute(LangfuseAttributes.traceMetadata("result"), result.traceValue());
                root.setAttribute(LangfuseAttributes.traceMetadata("history_turns"), String.valueOf(historyTurns));
                root.setAttribute(LangfuseAttributes.traceMetadata("context_fragments"), String.valueOf(delivered));
                Instant firstToken = firstTokenAt;
                if (firstToken != null) {
                    root.setAttribute(LangfuseAttributes.traceMetadata("time_to_first_token_ms"),
                            String.valueOf(Duration.between(startedAt, firstToken).toMillis()));
                }
                if (truncated.get()) {
                    root.setAttribute(LangfuseAttributes.traceMetadata("truncated"), "true");
                }
                if (result == TurnResult.ERROR) {
                    FailureStage stage = failedStage == null ? FailureStage.GENERATION : failedStage;
                    String code = failureCode == null ? INTERNAL_ERROR_CODE : failureCode;
                    root.setAttribute(LangfuseAttributes.traceMetadata("error_code"), code);
                    root.setAttribute(LangfuseAttributes.traceMetadata("failed_stage"), stage.traceValue());
                    fail(root, "The turn failed with " + code);
                } else if (result == TurnResult.CANCELLED) {
                    root.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_WARNING);
                }
                root.end();
            });
        }

        /** One call to the model; it ends once, whichever of complete, fail and cancel comes first. */
        private final class Generation implements GenerationScope {

            private final Span span;
            private final String model;
            private final String providerName;
            private final AtomicBoolean ended = new AtomicBoolean();
            private volatile Instant firstToken;

            private Generation(String model, Map<String, Object> parameters, List<PromptMessage> messages) {
                this.model = model;
                Map<String, Object> modelParameters = new LinkedHashMap<>(parameters);
                Object declaredProvider = modelParameters.remove(PROVIDER_PARAMETER);
                this.providerName = declaredProvider == null ? "" : declaredProvider.toString();
                this.span = child(LangfuseAttributes.GENERATION_SPAN, root).startSpan();
                common(span);
                span.setAttribute(LangfuseAttributes.OBSERVATION_TYPE, LangfuseAttributes.TYPE_GENERATION);
                span.setAttribute(LangfuseAttributes.MODEL_NAME, model);
                span.setAttribute(LangfuseAttributes.MODEL_PARAMETERS, TraceJson.write(modelParameters));
                span.setAttribute(LangfuseAttributes.OBSERVATION_INPUT, prompt(messages));
            }

            private String prompt(List<PromptMessage> messages) {
                List<Map<String, String>> items = new ArrayList<>();
                for (PromptMessage message : messages) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", text(message.content()));
                    items.add(item);
                }
                return TraceJson.write(items);
            }

            @Override
            public void firstToken() {
                if (firstToken == null) {
                    Instant now = Instant.now();
                    firstToken = now;
                    if (firstTokenAt == null) {
                        firstTokenAt = now;
                    }
                }
            }

            @Override
            public void complete(TokenUsage usage, String answerText) {
                end("the end of the generation", () -> {
                    span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, text(answerText));
                    usage(usage);
                    Instant first = firstToken;
                    if (first != null) {
                        span.setAttribute(LangfuseAttributes.COMPLETION_START_TIME, first.toString());
                    }
                });
            }

            @Override
            public void fail(Throwable error) {
                end("the failure of the generation", () -> {
                    if (failedStage == null) {
                        failedStage = FailureStage.GENERATION;
                    }
                    String detail = error instanceof HelpdeskException typed
                            ? typed.getResponseCode().getCode() : error.getClass().getSimpleName();
                    Turn.this.fail(span, "The model failed: " + detail);
                });
            }

            @Override
            public void cancel(String partialAnswer) {
                end("the cancellation of the generation", () -> {
                    String partial = partialAnswer;
                    if (partial == null) {
                        synchronized (answer) {
                            partial = answer.toString();
                        }
                    }
                    span.setAttribute(LangfuseAttributes.OBSERVATION_OUTPUT, text(partial));
                    span.setAttribute(LangfuseAttributes.OBSERVATION_LEVEL, LangfuseAttributes.LEVEL_WARNING);
                });
            }

            private void end(String what, Runnable action) {
                if (!ended.compareAndSet(false, true)) {
                    return;
                }
                safely(what, action);
                span.end();
            }

            private void usage(TokenUsage usage) {
                Map<String, Object> details = new LinkedHashMap<>();
                if (usage.isKnown()) {
                    details.put("input", usage.inputTokens());
                    details.put("output", usage.outputTokens());
                    details.put("total", usage.total().orElse(0));
                    span.setAttribute(LangfuseAttributes.USAGE_DETAILS, TraceJson.write(details));
                }
                span.setAttribute(LangfuseAttributes.observationMetadata("usage_status"),
                        usage.isKnown() ? TracingConstants.USAGE_KNOWN : TracingConstants.USAGE_UNKNOWN);
                CostCalculator.Cost cost = costs.cost(model, providerName, usage);
                if (cost.hasAmounts()) {
                    Map<String, Object> amounts = new LinkedHashMap<>();
                    amounts.put("input", cost.input());
                    amounts.put("output", cost.output());
                    amounts.put("total", cost.total());
                    span.setAttribute(LangfuseAttributes.COST_DETAILS, TraceJson.write(amounts));
                }
                span.setAttribute(LangfuseAttributes.observationMetadata("cost_status"), cost.status());
            }
        }
    }
}
