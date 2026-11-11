package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Talks to the local model through Spring AI; the rest of the module only knows {@link ChatModelPort}.
 *
 * <p>It streams from the {@link ChatModel} directly instead of {@code ChatClient.stream()}: the client's terminal
 * advisor re-publishes every stream on {@code Schedulers.boundedElastic()}, which pins one thread per open stream and
 * defeats the point of the reactive web layer (ADR-0011). For the same reason the context is not added by a
 * {@code RetrievalAugmentationAdvisor} (its retrieval is synchronous) but by the {@link QueryAugmenter} that advisor
 * is built on, with fragments the use case already retrieved without blocking. The request sent to the model is the
 * same.
 */
@Component
public class OllamaChatAdapter implements ChatModelPort {

    private static final String PROVIDER = "ollama";
    private static final String PROVIDER_PARAMETER = "provider";
    private static final String TITLE_KEY = "title";
    private static final String NUMBER_KEY = "number";
    private static final String SECTION_KEY = "section";
    private static final String SECTION_SEPARATOR = " › ";
    private static final String FRAGMENT_SEPARATOR = "\n\n";
    private static final ToolCallingManager TOOL_CALLS = ToolCallingManager.builder()
            .toolExecutionExceptionProcessor(
                    DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true).build())
            .build();

    private final ChatModel chatModel;
    private final String systemPrompt;
    private final String emptyContextTemplate;
    private final QueryAugmenter augmenter;
    private final ToolCallbackComposer tools;

    public OllamaChatAdapter(ChatModel chatModel, ChatProperties properties, RagProperties ragProperties,
            ToolCallbackComposer tools) {
        this.chatModel = chatModel;
        this.systemPrompt = properties.systemPrompt();
        this.emptyContextTemplate = ragProperties.emptyContextTemplate();
        // Only used with context: its own empty-context path would drop the question, see userText.
        this.augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(ragProperties.contextTemplate()))
                .documentFormatter(OllamaChatAdapter::formatDocuments)
                .build();
        this.tools = tools;
    }

    /** Any failure of the model is a {@link ChatModelException}; the use case decides what it means for the stream. */
    @Override
    public Flux<String> streamAnswer(String message, List<ConversationTurn> history, List<ContextFragment> context) {
        return Flux.deferContextual(reactorContext -> {
            TurnTrace trace = reactorContext.getOrDefault(TracingConstants.TURN_TRACE_KEY, TurnTrace.NONE);
            List<Message> messages = messages(message, history, context);
            List<ToolCallback> callbacks = tools.compose(trace);
            Prompt prompt = callbacks.isEmpty() ? new Prompt(messages) : new Prompt(messages, withTools(callbacks));
            GenerationScope generation = startGeneration(trace, messages);
            StringBuffer answer = new StringBuffer();
            AtomicReference<TokenUsage> usage = new AtomicReference<>(TokenUsage.unknown());
            AtomicBoolean firstFragment = new AtomicBoolean();
            return streamResponses(prompt)
                    .doOnNext(response -> usage.accumulateAndGet(usageOf(response), TokenUsage::plus))
                    .mapNotNull(response -> response.getResult() == null ? null
                            : response.getResult().getOutput().getText())
                    .filter(fragment -> !fragment.isEmpty())
                    .doOnNext(fragment -> {
                        if (firstFragment.compareAndSet(false, true)) {
                            generation.firstToken();
                        }
                        answer.append(fragment);
                    })
                    .onErrorMap(error -> !(error instanceof HelpdeskException), ChatModelException::new)
                    .doOnError(generation::fail)
                    .doOnComplete(() -> generation.complete(usage.get(), answer.toString()))
                    .doOnCancel(() -> generation.cancel(answer.toString()));
        });
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

    /** The trace is told what is about to be sent; if it fails the answer goes on without it. */
    private GenerationScope startGeneration(TurnTrace trace, List<Message> messages) {
        try {
            ChatOptions options = chatModel.getOptions();
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put(PROVIDER_PARAMETER, PROVIDER);
            if (options != null) {
                putIfPresent(parameters, "temperature", options.getTemperature());
                putIfPresent(parameters, "topP", options.getTopP());
                putIfPresent(parameters, "maxTokens", options.getMaxTokens());
            }
            String model = options == null || options.getModel() == null ? "unknown" : options.getModel();
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
     * the chunks that carry no count (Ollama sends them in the last one only), so zero in both sides is unknown.
     */
    private static TokenUsage usageOf(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
                || (usage.getPromptTokens() == 0 && usage.getCompletionTokens() == 0)) {
            return TokenUsage.unknown();
        }
        return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

    /**
     * The system prompt, the earlier turns as the customer's and the assistant's own messages (never as system text,
     * so nothing said before can replace the rules), and the current question with its context.
     */
    private List<Message> messages(String message, List<ConversationTurn> history, List<ContextFragment> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        for (ConversationTurn turn : history) {
            messages.add(new UserMessage(turn.question()));
            messages.add(new AssistantMessage(turn.answer()));
        }
        messages.add(new UserMessage(userText(message, context)));
        return messages;
    }

    /**
     * The augmenter's own empty-context path would replace the question with a fixed text, so without context the
     * question is placed in the empty-context template here.
     */
    private String userText(String message, List<ContextFragment> context) {
        if (context.isEmpty()) {
            return emptyContextTemplate.replace(ChatConstants.QUERY_PLACEHOLDER, message);
        }
        List<Document> documents = IntStream.range(0, context.size())
                .mapToObj(index -> toDocument(index + 1, context.get(index)))
                .toList();
        return augmenter.augment(new Query(message), documents).text();
    }

    private static Document toDocument(int number, ContextFragment fragment) {
        return Document.builder()
                .text(fragment.text())
                .metadata(Map.of(NUMBER_KEY, number, TITLE_KEY, fragment.title(), SECTION_KEY, fragment.section()))
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

    /** Per-request options replace the defaults instead of merging, so the tools go on a copy of the defaults. */
    private ChatOptions withTools(List<ToolCallback> callbacks) {
        ToolCallingChatOptions.Builder<?> builder = chatModel.getOptions() instanceof ToolCallingChatOptions d
                ? d.mutate()
                : ToolCallingChatOptions.builder();
        return builder.toolCallbacks(callbacks).build();
    }
}
