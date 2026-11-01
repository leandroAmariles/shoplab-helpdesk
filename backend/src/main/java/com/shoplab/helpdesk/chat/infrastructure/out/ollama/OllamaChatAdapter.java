package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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

    private static final String SOURCE_KEY = "source";
    private static final String SECTION_KEY = "section";
    private static final String SECTION_SEPARATOR = " › ";
    private static final String FRAGMENT_SEPARATOR = "\n\n";

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
        this.augmenter = ContextualQueryAugmenter.builder()
                .promptTemplate(new PromptTemplate(ragProperties.contextTemplate()))
                .emptyContextPromptTemplate(new PromptTemplate(ragProperties.emptyContextTemplate()))
                .allowEmptyContext(false)
                .documentFormatter(OllamaChatAdapter::formatDocuments)
                .build();
        this.tools = tools;
    }

    /** Any failure of the model is a {@link ChatModelException}; the use case decides what it means for the stream. */
    @Override
    public Flux<String> streamAnswer(String message, List<ContextFragment> context) {
        return Flux.defer(() -> {
            List<Message> messages = List.of(new SystemMessage(systemPrompt),
                    new UserMessage(userText(message, context)));
            List<ToolCallback> callbacks = tools.compose();
            Prompt prompt = callbacks.isEmpty() ? new Prompt(messages) : new Prompt(messages, withTools(callbacks));
            return chatModel.stream(prompt)
                    .mapNotNull(response -> response.getResult() == null ? null
                            : response.getResult().getOutput().getText())
                    .filter(fragment -> !fragment.isEmpty())
                    .onErrorMap(error -> !(error instanceof HelpdeskException), ChatModelException::new);
        });
    }

    /**
     * The augmenter's own empty-context path would replace the question with a fixed text, so without context the
     * question is placed in the empty-context template here.
     */
    private String userText(String message, List<ContextFragment> context) {
        if (context.isEmpty()) {
            return emptyContextTemplate.replace(ChatConstants.QUERY_PLACEHOLDER, message);
        }
        List<Document> documents = context.stream().map(OllamaChatAdapter::toDocument).toList();
        return augmenter.augment(new Query(message), documents).text();
    }

    private static Document toDocument(ContextFragment fragment) {
        return Document.builder()
                .text(fragment.text())
                .metadata(Map.of(SOURCE_KEY, fragment.source(), SECTION_KEY, fragment.section()))
                .score(fragment.score())
                .build();
    }

    /** Each fragment goes under its origin, {@code [source › section]}, so the model can tell them apart. */
    private static String formatDocuments(List<Document> documents) {
        return documents.stream()
                .map(document -> origin(document) + "\n" + document.getText())
                .collect(Collectors.joining(FRAGMENT_SEPARATOR));
    }

    private static String origin(Document document) {
        Object source = document.getMetadata().get(SOURCE_KEY);
        Object section = document.getMetadata().get(SECTION_KEY);
        boolean hasSection = section != null && !section.toString().isBlank();
        return "[" + source + (hasSection ? SECTION_SEPARATOR + section : "") + "]";
    }

    /** Per-request options replace the defaults instead of merging, so the tools go on a copy of the defaults. */
    private ChatOptions withTools(List<ToolCallback> callbacks) {
        ToolCallingChatOptions.Builder<?> builder = chatModel.getOptions() instanceof ToolCallingChatOptions d
                ? d.mutate()
                : ToolCallingChatOptions.builder();
        return builder.toolCallbacks(callbacks).build();
    }
}
