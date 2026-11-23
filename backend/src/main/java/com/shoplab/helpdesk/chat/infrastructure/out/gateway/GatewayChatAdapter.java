package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.application.port.out.SmallTalkModelPort;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.application.usecase.PromptRenderer;
import com.shoplab.helpdesk.chat.config.GatewayProperties;
import com.shoplab.helpdesk.chat.config.SmallTalkProperties;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.PromptOverrides;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.ServedModel;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.GatewayConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.SmallTalkConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.shared.constants.GatewayHeaders;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * Talks to the model gateway (an OpenAI-compatible API) through Spring AI; the rest of the module only knows
 * {@link ChatModelPort}. The backend asks for the logical model {@code chat}: which real model answers, the fallback,
 * the budget and the cost are the gateway's business (LAB-84).
 *
 * <p>It streams from the {@link ChatModel} directly instead of {@code ChatClient.stream()}: the client's terminal
 * advisor re-publishes every stream on {@code Schedulers.boundedElastic()}, which pins one thread per open stream and
 * defeats the point of the reactive web layer (ADR-0011). For the same reason the context is not added by a
 * {@code RetrievalAugmentationAdvisor} (its retrieval is synchronous) but rendered here, with fragments the use case
 * already retrieved without blocking. The request sent to the model is the same.
 *
 * <p>The text of the prompts is not here (LAB-85): each turn asks the {@link PromptCatalogPort} for the ones it needs
 * when it starts and keeps them until it ends, so a change of label never reaches a turn that is in progress. A request
 * that fixes versions (an evaluation) gets those versions, and fails if one is not available.
 *
 * <p>Each call tells the gateway which conversation and which chat request it belongs to (the {@code user} field and
 * the request-id header) so the gateway's logs can be crossed with the backend's and with the traces.
 */
@Component
public class GatewayChatAdapter implements ChatModelPort, SmallTalkModelPort {

    /** The trace prices by provider; the gateway fronts local Ollama models, whose cost in traces is 0 (LAB-64). */
    private static final String PROVIDER = "ollama";
    private static final String PROVIDER_PARAMETER = "provider";
    private static final String TITLE_KEY = "title";
    private static final String NUMBER_KEY = "number";
    private static final String SECTION_KEY = "section";
    private static final String SECTION_SEPARATOR = " › ";
    private static final String FRAGMENT_SEPARATOR = "\n\n";
    private static final String QUERY_VARIABLE = "query";
    private static final String CONTEXT_VARIABLE = "context";
    private static final ToolCallingManager TOOL_CALLS = ToolCallingManager.builder()
            .toolExecutionExceptionProcessor(
                    DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
            .build();

    private final ChatModel chatModel;
    private final PromptCatalogPort prompts;
    private final SmallTalkProperties smallTalk;
    private final ToolCallbackComposer tools;
    private final String primaryModel;

    public GatewayChatAdapter(ChatModel chatModel, PromptCatalogPort prompts, ToolCallbackComposer tools,
            GatewayProperties gateway, SmallTalkProperties smallTalk) {
        this.chatModel = chatModel;
        this.prompts = prompts;
        this.smallTalk = smallTalk;
        this.tools = tools;
        this.primaryModel = gateway.primaryModel();
    }

    /** Any failure of the model is a typed exception; the use case decides what it means for the stream. */
    @Override
    public Flux<String> streamAnswer(String message, List<ConversationTurn> history, List<ContextFragment> context) {
        List<PromptName> needed = List.of(PromptName.CHAT_SYSTEM,
                context.isEmpty() ? PromptName.EMPTY_CONTEXT : PromptName.CONTEXT);
        return generate(needed, resolved -> messages(message, history, context, resolved), true);
    }

    /**
     * The answer to a courtesy: its own minimal instructions and the message, nothing else. No history, no fragments
     * and no tools, so the only customer text that reaches this prompt is a courtesy of the closed vocabulary.
     */
    @Override
    public Flux<String> streamReply(String message) {
        return generate(List.of(PromptName.SMALL_TALK), resolved -> {
            String instructions = resolved.get(PromptName.SMALL_TALK).text()
                    .replace(SmallTalkConstants.TOPICS_PLACEHOLDER, smallTalk.topics());
            return List.of(new SystemMessage(instructions), new UserMessage(PromptData.wrap(message)));
        }, false);
    }

    /**
     * @param needed         the prompts the turn uses, the main one first (the trace links the generation with it)
     * @param promptMessages builds the messages from the prompts the turn took when it started
     */
    private Flux<String> generate(List<PromptName> needed,
            Function<Map<PromptName, ResolvedPrompt>, List<Message>> promptMessages, boolean withTools) {
        return Flux.deferContextual(reactorContext -> {
            PromptOverrides overrides = reactorContext.<PromptOverrides>getOrEmpty(PromptConstants.OVERRIDES_CONTEXT_KEY)
                    .orElse(PromptOverrides.NONE);
            return resolve(needed, overrides)
                    .flatMapMany(resolved -> generate(reactorContext, needed, resolved, !overrides.isEmpty(),
                            promptMessages.apply(resolved), withTools));
        });
    }

    /**
     * The prompts of the turn, read once: the version the chat serves now, or the one the request fixes. Nothing here
     * waits for Langfuse unless a version is fixed, and then a version that is not available is an error.
     */
    private Mono<Map<PromptName, ResolvedPrompt>> resolve(List<PromptName> needed, PromptOverrides overrides) {
        return Flux.fromIterable(needed)
                .concatMap(name -> overrides.versionOf(name)
                        .map(version -> prompts.pinned(name, version))
                        .orElseGet(() -> Mono.fromSupplier(() -> prompts.current(name))))
                .collectMap(prompt -> prompt.reference().name(), prompt -> prompt,
                        () -> new EnumMap<>(PromptName.class));
    }

    private Flux<String> generate(ContextView reactorContext, List<PromptName> needed,
            Map<PromptName, ResolvedPrompt> resolved, boolean overridden, List<Message> messages,
            boolean withTools) {
        TurnTrace trace = reactorContext.getOrDefault(TracingConstants.TURN_TRACE_KEY, TurnTrace.NONE);
        String conversationId = reactorContext.getOrDefault(GatewayConstants.CONVERSATION_ID_CONTEXT_KEY, null);
        String requestId = reactorContext.getOrDefault(GatewayHeaders.REQUEST_ID_CONTEXT_KEY, null);
        List<ToolCallback> callbacks = withTools ? tools.compose(trace) : List.of();
        ChatOptions options = requestOptions(callbacks, conversationId, requestId);
        Prompt prompt = options == null ? new Prompt(messages) : new Prompt(messages, options);
        announcePrompts(trace, needed, resolved, overridden);
        GenerationScope generation = startGeneration(trace, messages);
        StringBuffer answer = new StringBuffer();
        AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.unknown());
        AtomicReference<String> servedBy = new AtomicReference<>();
        AtomicBoolean firstFragment = new AtomicBoolean();
        return streamResponses(prompt)
                .doOnNext(response -> {
                    usage.accumulateAndGet(usageOf(response), TokenUsage::plus);
                    String model = response.getMetadata().getModel();
                    if (!model.isBlank()) {
                        servedBy.set(model);
                    }
                })
                .mapNotNull(response -> response.getResult() == null ? null
                        : response.getResult().getOutput().getText())
                .filter(fragment -> !fragment.isEmpty())
                .transform(DelimiterEchoFilter::strip)
                .doOnNext(fragment -> {
                    if (firstFragment.compareAndSet(false, true)) {
                        generation.firstToken();
                    }
                    answer.append(fragment);
                })
                .onErrorMap(error -> !(error instanceof HelpdeskException), GatewayErrorTranslator::translate)
                .doOnError(generation::fail)
                .doOnComplete(() -> generation.complete(usage.get(), answer.toString(),
                        ServedModel.of(servedBy.get(), primaryModel)))
                .doOnCancel(() -> generation.cancel(answer.toString()));
    }

    private Flux<ChatResponse> streamResponses(Prompt prompt) {
        return chatModel.stream(prompt)
                .concatMap(response -> response.hasToolCalls() ? executeToolsAndContinue(prompt, response)
                        : Flux.just(response));
    }

    private Flux<ChatResponse> executeToolsAndContinue(Prompt prompt, ChatResponse toolCallResponse) {
        return Mono.fromCallable(() -> TOOL_CALLS.executeToolCalls(prompt, toolCallResponse))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(result -> Flux.just(toolCallResponse)
                        .concatWith(streamResponses(new Prompt(result.conversationHistory(), prompt.getOptions()))));
    }

    /**
     * The trace is told which prompts, and which versions of them, the turn took; the first one is the main prompt.
     * It has to be told before the generation starts, which is where the trace links the version. If it fails the
     * answer goes on without it.
     */
    private static void announcePrompts(TurnTrace trace, List<PromptName> needed,
            Map<PromptName, ResolvedPrompt> resolved, boolean overridden) {
        try {
            List<PromptReference> used = needed.stream().map(name -> resolved.get(name).reference()).toList();
            trace.promptsUsed(used, overridden);
        } catch (RuntimeException e) {
            // A trace never breaks an answer.
        }
    }

    /** The trace is told what is about to be sent; if it fails the answer goes on without it. */
    private GenerationScope startGeneration(TurnTrace trace, List<Message> messages) {
        try {
            ChatOptions options = chatModel.getOptions();
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(PROVIDER_PARAMETER, PROVIDER);
            putIfPresent(parameters, "temperature", options.getTemperature());
            putIfPresent(parameters, "topP", options.getTopP());
            putIfPresent(parameters, "maxTokens", options.getMaxTokens());
            String model = options.getModel() == null ? "unknown" : options.getModel();
            List<PromptMessage> prompt = messages.stream()
                    .map(item -> new PromptMessage(roleOf(item), item.getText()))
                    .toList();
            return trace.startGeneration(model, parameters, prompt);
        } catch (RuntimeException e) {
            return GenerationScope.NONE;
        }
    }

    private static void putIfPresent(Map<String, Object> parameters, String name, Object value) {
        if (value != null) {
            parameters.put(name, value);
        }
    }

    private static String roleOf(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            default -> "user";
        };
    }

    /**
     * The tokens a chunk of the answer reports. Spring AI gives zeros both when the model did not report them and for
     * the chunks that carry no count (the gateway sends them in the last one only), so zero in both sides is unknown.
     */
    private static TokenUsage usageOf(ChatResponse response) {
        Usage usage = response.getMetadata().getUsage();
        if (usage.getPromptTokens() == 0 && usage.getCompletionTokens() == 0) {
            return TokenUsage.unknown();
        }
        return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /**
     * The system prompt, the earlier turns as the customer's and the assistant's own messages (never as system text,
     * so nothing said before can replace the rules), and the current question with its context.
     */
    private List<Message> messages(String message, List<ConversationTurn> history, List<ContextFragment> context,
            Map<PromptName, ResolvedPrompt> resolved) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(resolved.get(PromptName.CHAT_SYSTEM).text()));
        for (ConversationTurn turn : history) {
            messages.add(new UserMessage(PromptData.wrap(turn.question())));
            messages.add(new AssistantMessage(PromptData.neutralize(turn.answer())));
        }
        messages.add(new UserMessage(userText(message, context, resolved)));
        return messages;
    }

    /** Without context the question goes in the empty-context template; with it, in the context template. */
    private String userText(String message, List<ContextFragment> context, Map<PromptName, ResolvedPrompt> resolved) {
        String delimited = PromptData.wrap(message);
        if (context.isEmpty()) {
            return resolved.get(PromptName.EMPTY_CONTEXT).text().replace(ChatConstants.QUERY_PLACEHOLDER, delimited);
        }
        List<Document> documents = IntStream.range(0, context.size())
                .mapToObj(index -> toDocument(index + 1, context.get(index)))
                .toList();
        return PromptRenderer.render(resolved.get(PromptName.CONTEXT).text(),
                Map.of(QUERY_VARIABLE, delimited, CONTEXT_VARIABLE, formatDocuments(documents)));
    }

    private static Document toDocument(int number, ContextFragment fragment) {
        return Document.builder()
                .id(Integer.toString(number))
                .text(PromptData.neutralize(fragment.text()))
                .metadata(Map.of(NUMBER_KEY, number, TITLE_KEY, PromptData.neutralize(fragment.title()),
                        SECTION_KEY, PromptData.neutralize(fragment.section())))
                .score(fragment.score())
                .build();
    }

    /**
     * Each fragment goes under its number and origin, {@code [n] Title › section}: the number is what the model cites
     * as {@code [n]} and what the customer sees next to the source (LAB-23).
     */
    private static String formatDocuments(List<Document> documents) {
        return documents.stream()
                .map(document -> origin(document) + "\n" + document.getText())
                .collect(Collectors.joining(FRAGMENT_SEPARATOR));
    }

    private static String origin(Document document) {
        Object section = document.getMetadata().get(SECTION_KEY);
        boolean hasSection = section != null && !section.toString().isBlank();
        return "[" + document.getMetadata().get(NUMBER_KEY) + "] " + document.getMetadata().get(TITLE_KEY)
                + (hasSection ? SECTION_SEPARATOR + section : "");
    }

    /**
     * Per-request options replace the defaults instead of merging, so what the call needs (the tools, and the
     * conversation and request ids for the gateway) goes on a copy of the defaults. {@code null} when there is
     * nothing to add.
     */
    private ChatOptions requestOptions(List<ToolCallback> callbacks, String conversationId, String requestId) {
        boolean correlated = conversationId != null || requestId != null;
        if (callbacks.isEmpty() && !correlated) {
            return null;
        }
        if (chatModel.getOptions() instanceof OpenAiChatOptions defaults) {
            OpenAiChatOptions.Builder builder = defaults.mutate();
            if (conversationId != null) {
                builder.user(conversationId);
            }
            if (requestId != null) {
                builder.customHeaders(Map.of(GatewayHeaders.REQUEST_ID_HEADER, requestId));
            }
            if (!callbacks.isEmpty()) {
                builder.toolCallbacks(callbacks);
            }
            return builder.build();
        }
        return callbacks.isEmpty() ? null : withTools(callbacks);
    }

    private ChatOptions withTools(List<ToolCallback> callbacks) {
        ToolCallingChatOptions.Builder<?> builder = chatModel.getOptions() instanceof ToolCallingChatOptions d
                ? d.mutate()
                : ToolCallingChatOptions.builder();
        return builder.toolCallbacks(callbacks).build();
    }
}
