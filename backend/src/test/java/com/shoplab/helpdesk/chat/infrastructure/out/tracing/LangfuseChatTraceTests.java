package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import com.shoplab.helpdesk.chat.config.TracingProperties;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The spans of a turn match contracts/trace-schema.md (specs/LAB-64, US2 and US5). */
class LangfuseChatTraceTests {

    private static final String SECRET = "sk-lf-very-secret";
    private static final String MODEL = "llama3.2:3b";
    private static final List<PromptMessage> PROMPT = List.of(
            new PromptMessage("system", "Answer only with the context."),
            new PromptMessage("user", "[1] Returns\nTen days.\n\nQuestion: Deadline?"));

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final DerivedTraceIdGenerator ids = new DerivedTraceIdGenerator();
    private final SdkTracerProvider provider = SdkTracerProvider.builder().setIdGenerator(ids)
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    @AfterEach
    void close() {
        provider.close();
    }

    private LangfuseChatTrace trace(int maxContent) {
        return new LangfuseChatTrace(provider, ids, new TracingProperties(true, null, "pk-lf-public", SECRET, "dev",
                maxContent, null, null, null, null, null, null));
    }

    private LangfuseChatTrace trace() {
        return trace(20_000);
    }

    private static StreamChatCommand command() {
        return new StreamChatCommand("Deadline?", "req-1", "0b0e6c8e-5a4f-4d58-9d44-0f7c3d1d4a11");
    }

    private static ContextFragment fragment(String text, double score) {
        return new ContextFragment("returns.md", "Returns", "Deadlines", 2, text, score);
    }

    private static String attr(SpanData span, AttributeKey<String> key) {
        return span.getAttributes().get(key);
    }

    private static String meta(SpanData span, String name) {
        return attr(span, LangfuseAttributes.traceMetadata(name));
    }

    private static String observationMeta(SpanData span, String name) {
        return attr(span, LangfuseAttributes.observationMetadata(name));
    }

    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream().filter(item -> item.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span " + name + " in "
                        + exporter.getFinishedSpanItems().stream().map(SpanData::getName).toList()));
    }

    private void run(LangfuseChatTrace trace, Function<com.shoplab.helpdesk.chat.application.port.out.TurnTrace,
            Flux<ChatEvent>> turn) {
        StepVerifier.create(trace.traceTurn(command(), turn)).thenConsumeWhile(event -> true).verifyComplete();
    }

    private Flux<ChatEvent> completedTurn(com.shoplab.helpdesk.chat.application.port.out.TurnTrace turn) {
        turn.historyTurns(1);
        turn.retrieval(new RetrievalTrace("Deadline?", 3, List.of(fragment("Ten days.", 0.91),
                fragment("Five days.", 0.74)), false), Duration.ofMillis(40));
        GenerationScope generation = turn.startGeneration(MODEL, Map.of("provider", "ollama", "temperature", 0.2),
                PROMPT);
        generation.firstToken();
        generation.complete(new TokenUsage(11, 7), "Ten days [1].");
        return Flux.just(new TokenEvent("Ten days "), new TokenEvent("[1]."), new DoneEvent(2));
    }

    // --- the completed turn ---

    @Test
    void aCompletedTurnIsOneTreeOfThreeSpans() {
        run(trace(), this::completedTurn);

        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("chat-turn", "retrieval", "generation");
        SpanData root = span("chat-turn");
        assertThat(root.getParentSpanContext().isValid()).isFalse();
        assertThat(span("retrieval").getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(span("generation").getParentSpanId()).isEqualTo(root.getSpanId());
        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getTraceId).containsOnly(root.getTraceId());
    }

    @Test
    void theTraceIdIsDerivedFromTheRequestId() {
        run(trace(), this::completedTurn);

        assertThat(span("chat-turn").getTraceId()).isEqualTo(DerivedTraceIdGenerator.traceIdOf("req-1"));
    }

    @Test
    void theRootCarriesTheQuestionTheAnswerAndTheTurnMetadata() {
        run(trace(), this::completedTurn);

        SpanData root = span("chat-turn");
        assertThat(attr(root, LangfuseAttributes.TRACE_NAME)).isEqualTo("chat-turn");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("span");
        assertThat(attr(root, LangfuseAttributes.SESSION_ID)).isEqualTo("0b0e6c8e-5a4f-4d58-9d44-0f7c3d1d4a11");
        assertThat(attr(root, LangfuseAttributes.ENVIRONMENT)).isEqualTo("dev");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_INPUT)).isEqualTo("Deadline?");
        assertThat(attr(root, LangfuseAttributes.TRACE_INPUT)).isEqualTo("Deadline?");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Ten days [1].");
        assertThat(attr(root, LangfuseAttributes.TRACE_OUTPUT)).isEqualTo("Ten days [1].");
        assertThat(root.getAttributes().get(LangfuseAttributes.TRACE_TAGS)).containsExactly("chat");
        assertThat(meta(root, "request_id")).isEqualTo("req-1");
        assertThat(meta(root, "result")).isEqualTo("completed");
        assertThat(meta(root, "history_turns")).isEqualTo("1");
        assertThat(meta(root, "context_fragments")).isEqualTo("2");
        assertThat(meta(root, "time_to_first_token_ms")).isNotNull();
        assertThat(meta(root, "truncated")).isNull();
        assertThat(meta(root, "error_code")).isNull();
        assertThat(root.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void theRetrievalListsTheFragmentsHandedToTheModelWithTheirScores() {
        run(trace(), this::completedTurn);

        SpanData retrieval = span("retrieval");
        assertThat(attr(retrieval, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("retriever");
        assertThat(attr(retrieval, LangfuseAttributes.OBSERVATION_INPUT)).isEqualTo("Deadline?");
        assertThat(attr(retrieval, LangfuseAttributes.OBSERVATION_OUTPUT))
                .contains("\"number\":1", "\"source\":\"returns.md\"", "\"title\":\"Returns\"",
                        "\"section\":\"Deadlines\"", "\"position\":2", "\"score\":0.91", "\"text\":\"Ten days.\"",
                        "\"number\":2", "\"score\":0.74");
        assertThat(observationMeta(retrieval, "candidates")).isEqualTo("3");
        assertThat(observationMeta(retrieval, "delivered")).isEqualTo("2");
        assertThat(observationMeta(retrieval, "best_score")).isEqualTo("0.91");
        assertThat(retrieval.getEndEpochNanos() - retrieval.getStartEpochNanos())
                .isGreaterThanOrEqualTo(Duration.ofMillis(40).toNanos());
    }

    @Test
    void theGenerationShowsThePromptTheModelTheTokensAndTheCost() {
        run(trace(), this::completedTurn);

        SpanData generation = span("generation");
        assertThat(attr(generation, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("generation");
        assertThat(attr(generation, LangfuseAttributes.MODEL_NAME)).isEqualTo(MODEL);
        assertThat(attr(generation, LangfuseAttributes.MODEL_PARAMETERS)).contains("\"temperature\":0.2")
                .doesNotContain("provider");
        assertThat(attr(generation, LangfuseAttributes.OBSERVATION_INPUT))
                .contains("\"role\":\"system\"", "Answer only with the context.", "\"role\":\"user\"", "Ten days.");
        assertThat(attr(generation, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Ten days [1].");
        assertThat(attr(generation, LangfuseAttributes.USAGE_DETAILS))
                .isEqualTo("{\"input\":11,\"output\":7,\"total\":18}");
        assertThat(attr(generation, LangfuseAttributes.COST_DETAILS))
                .isEqualTo("{\"input\":0.0,\"output\":0.0,\"total\":0.0}");
        assertThat(observationMeta(generation, "usage_status")).isEqualTo("known");
        assertThat(observationMeta(generation, "cost_status")).isEqualTo("priced");
        assertThat(attr(generation, LangfuseAttributes.COMPLETION_START_TIME)).isNotBlank();
        assertThat(attr(generation, LangfuseAttributes.SESSION_ID)).isNotNull();
    }

    @Test
    void withoutAConversationThereIsNoSession() {
        StepVerifier.create(trace().traceTurn(new StreamChatCommand("Deadline?", "req-2"),
                this::completedTurn)).thenConsumeWhile(event -> true).verifyComplete();

        assertThat(attr(span("chat-turn"), LangfuseAttributes.SESSION_ID)).isNull();
    }

    // --- usage and cost that cannot be known ---

    @Test
    void anUnknownUsageIsNotWrittenAsZeroAndHasNoCost() {
        run(trace(), turn -> {
            GenerationScope generation = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            generation.complete(TokenUsage.unknown(), "Hi");
            return Flux.just(new TokenEvent("Hi"), new DoneEvent(1));
        });

        SpanData generation = span("generation");
        assertThat(attr(generation, LangfuseAttributes.USAGE_DETAILS)).isNull();
        assertThat(attr(generation, LangfuseAttributes.COST_DETAILS)).isNull();
        assertThat(observationMeta(generation, "usage_status")).isEqualTo("unknown");
        assertThat(observationMeta(generation, "cost_status")).isEqualTo("unknown_usage");
    }

    @Test
    void aModelWithoutAPriceHasTokensButNoCost() {
        run(trace(), turn -> {
            GenerationScope generation = turn.startGeneration("mystery-model", Map.of("provider", "other"), PROMPT);
            generation.complete(new TokenUsage(5, 6), "Hi");
            return Flux.just(new TokenEvent("Hi"), new DoneEvent(1));
        });

        SpanData generation = span("generation");
        assertThat(attr(generation, LangfuseAttributes.USAGE_DETAILS)).isNotNull();
        assertThat(attr(generation, LangfuseAttributes.COST_DETAILS)).isNull();
        assertThat(observationMeta(generation, "cost_status")).isEqualTo("unpriced");
    }

    // --- tools ---

    @Test
    void aToolCallIsASpanInsideTheGeneration() {
        run(trace(), turn -> {
            GenerationScope generation = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            turn.toolCall(new ToolCallTrace("lookupOrder", "{\"id\":7}", "shipped", false, null),
                    Duration.ofMillis(5));
            generation.complete(new TokenUsage(1, 1), "Shipped.");
            return Flux.just(new TokenEvent("Shipped."), new DoneEvent(1));
        });

        SpanData tool = span("tool:lookupOrder");
        assertThat(tool.getParentSpanId()).isEqualTo(span("generation").getSpanId());
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_TYPE)).isEqualTo("tool");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_INPUT)).isEqualTo("{\"id\":7}");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("shipped");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_LEVEL)).isNull();
    }

    @Test
    void aFailedToolCallIsMarkedAsAnErrorWithoutItsResult() {
        run(trace(), turn -> {
            GenerationScope generation = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            turn.toolCall(new ToolCallTrace("lookupOrder", "{}", null, true, "timeout"), Duration.ofMillis(5));
            generation.complete(new TokenUsage(1, 1), "Sorry.");
            return Flux.just(new TokenEvent("Sorry."), new DoneEvent(1));
        });

        SpanData tool = span("tool:lookupOrder");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("ERROR");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)).isEqualTo("timeout");
        assertThat(attr(tool, LangfuseAttributes.OBSERVATION_OUTPUT)).isNull();
        assertThat(meta(span("chat-turn"), "result")).isEqualTo("completed");
    }

    // --- turns that are not a normal answer (US5) ---

    @Test
    void withoutContextAndAFixedMessageThereIsNoGenerationAndTheResultSaysSo() {
        run(trace(), turn -> {
            turn.historyTurns(0);
            turn.retrieval(new RetrievalTrace("Deadline?", 0, List.of(), false), Duration.ofMillis(3));
            turn.fixedMessageAnswer();
            return Flux.just(new TokenEvent("I do not know."), new DoneEvent(1));
        });

        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                .containsExactlyInAnyOrder("chat-turn", "retrieval");
        SpanData root = span("chat-turn");
        assertThat(meta(root, "result")).isEqualTo("no_context");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("I do not know.");
        assertThat(attr(span("retrieval"), LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("[]");
        assertThat(observationMeta(span("retrieval"), "best_score")).isNull();
    }

    @Test
    void aCancelledTurnKeepsThePartialAnswerAndIsAWarning() {
        GenerationScope[] open = new GenerationScope[1];
        StepVerifier.create(trace().traceTurn(command(), turn -> {
            open[0] = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            return Flux.<ChatEvent>just(new TokenEvent("Ten ")).concatWith(Flux.never());
        })).expectNext(new TokenEvent("Ten ")).thenCancel().verify();

        SpanData root = span("chat-turn");
        assertThat(meta(root, "result")).isEqualTo("cancelled");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Ten ");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("WARNING");
        SpanData generation = span("generation");
        assertThat(attr(generation, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Ten ");
        assertThat(attr(generation, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("WARNING");
    }

    @Test
    void aModelThatIsDownFailsTheGenerationWithTheCodeTheCustomerGot() {
        StepVerifier.create(trace().traceTurn(command(), turn -> {
            turn.retrieval(new RetrievalTrace("Deadline?", 1, List.of(fragment("Ten days.", 0.9)), false),
                    Duration.ofMillis(1));
            ChatModelException failure = new ChatModelException(new IllegalStateException("connection refused"));
            turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT).fail(failure);
            return Flux.error(failure);
        })).expectError(ChatModelException.class).verify();

        SpanData root = span("chat-turn");
        assertThat(meta(root, "result")).isEqualTo("error");
        assertThat(meta(root, "error_code")).isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE.getCode());
        assertThat(meta(root, "failed_stage")).isEqualTo("generation");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("ERROR");
        assertThat(root.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(attr(span("generation"), LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("ERROR");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_STATUS_MESSAGE)).doesNotContain("connection refused");
    }

    @Test
    void aFailureAfterTheFirstTokenIsTheErrorEventTheCustomerReceived() {
        run(trace(), turn -> {
            GenerationScope generation = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            generation.fail(new IllegalStateException("broke half-way"));
            return Flux.just(new TokenEvent("Ten "),
                    new ErrorEvent(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode(), "stream failed"));
        });

        SpanData root = span("chat-turn");
        assertThat(meta(root, "result")).isEqualTo("error");
        assertThat(meta(root, "error_code")).isEqualTo(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode());
        assertThat(meta(root, "failed_stage")).isEqualTo("generation");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Ten ");
    }

    @Test
    void aFailedSearchIsTheRetrievalStageAndThereIsNoGeneration() {
        StepVerifier.create(trace().traceTurn(command(), turn -> {
            turn.retrieval(new RetrievalTrace("Deadline?", 0, List.of(), true), Duration.ofMillis(2));
            return Flux.error(new KnowledgeRetrievalException(new IllegalStateException("db down")));
        })).expectError(KnowledgeRetrievalException.class).verify();

        SpanData root = span("chat-turn");
        assertThat(meta(root, "result")).isEqualTo("error");
        assertThat(meta(root, "failed_stage")).isEqualTo("retrieval");
        assertThat(meta(root, "error_code")).isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE.getCode());
        assertThat(attr(span("retrieval"), LangfuseAttributes.OBSERVATION_LEVEL)).isEqualTo("ERROR");
        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName).doesNotContain("generation");
    }

    @Test
    void aTurnIsClosedOnlyOnce() {
        GenerationScope[] generation = new GenerationScope[1];
        run(trace(), turn -> {
            generation[0] = turn.startGeneration(MODEL, Map.of("provider", "ollama"), PROMPT);
            generation[0].complete(new TokenUsage(1, 1), "Hi");
            return Flux.just(new TokenEvent("Hi"), new DoneEvent(1));
        });
        generation[0].cancel("late");
        generation[0].fail(new IllegalStateException("late"));

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        assertThat(attr(span("generation"), LangfuseAttributes.OBSERVATION_OUTPUT)).isEqualTo("Hi");
    }

    // --- limits and safety ---

    @Test
    void longContentIsCutAndTheTraceSaysSo() {
        StepVerifier.create(trace(10).traceTurn(
                new StreamChatCommand("A question that is far too long", "req-3"),
                turn -> Flux.just(new TokenEvent("An answer that is also too long"), new DoneEvent(1))))
                .thenConsumeWhile(event -> true).verifyComplete();

        SpanData root = span("chat-turn");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_INPUT)).startsWith("A question").endsWith("[truncated]");
        assertThat(attr(root, LangfuseAttributes.OBSERVATION_OUTPUT)).endsWith("[truncated]");
        assertThat(meta(root, "truncated")).isEqualTo("true");
    }

    @Test
    void noSpanCarriesTheKeysOrAuthorizationData() {
        run(trace(), this::completedTurn);

        for (SpanData span : exporter.getFinishedSpanItems()) {
            span.getAttributes().forEach((key, value) -> {
                assertThat(String.valueOf(value)).doesNotContain(SECRET).doesNotContain("pk-lf-public")
                        .doesNotContainIgnoringCase("authorization");
            });
        }
    }

    @Test
    void aFailureOfTheRecordingNeverReachesTheChat() {
        StepVerifier.create(trace().traceTurn(command(), turn -> {
            turn.retrieval(null, Duration.ofMillis(1));
            turn.toolCall(null, null);
            turn.startGeneration(null, null, null);
            return Flux.just(new TokenEvent("Hi"), new DoneEvent(1));
        })).expectNext(new TokenEvent("Hi"), new DoneEvent(1)).verifyComplete();
    }
}
