package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Talks to the local model through Spring AI; the rest of the module only knows {@link ChatModelPort}.
 *
 * <p>It streams from the {@link ChatModel} directly instead of {@code ChatClient.stream()}: the client's terminal
 * advisor re-publishes every stream on {@code Schedulers.boundedElastic()}, which pins one thread per open stream and
 * defeats the point of the reactive web layer (ADR-0011). The request sent to the model is the same.
 */
@Component
public class OllamaChatAdapter implements ChatModelPort {

    private final ChatModel chatModel;
    private final String systemPrompt;
    private final ToolCallbackComposer tools;

    public OllamaChatAdapter(ChatModel chatModel, ChatProperties properties, ToolCallbackComposer tools) {
        this.chatModel = chatModel;
        this.systemPrompt = properties.systemPrompt();
        this.tools = tools;
    }

    /** Any failure of the model is a {@link ChatModelException}; the use case decides what it means for the stream. */
    @Override
    public Flux<String> streamAnswer(String message) {
        return Flux.defer(() -> {
            List<Message> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(message));
            List<ToolCallback> callbacks = tools.compose();
            Prompt prompt = callbacks.isEmpty() ? new Prompt(messages) : new Prompt(messages, withTools(callbacks));
            return chatModel.stream(prompt)
                    .mapNotNull(response -> response.getResult() == null ? null
                            : response.getResult().getOutput().getText())
                    .filter(fragment -> !fragment.isEmpty())
                    .onErrorMap(error -> !(error instanceof HelpdeskException), ChatModelException::new);
        });
    }

    /** Per-request options replace the defaults instead of merging, so the tools go on a copy of the defaults. */
    private ChatOptions withTools(List<ToolCallback> callbacks) {
        ToolCallingChatOptions.Builder<?> builder = chatModel.getDefaultOptions() instanceof ToolCallingChatOptions d
                ? d.mutate()
                : ToolCallingChatOptions.builder();
        return builder.toolCallbacks(callbacks).build();
    }
}
