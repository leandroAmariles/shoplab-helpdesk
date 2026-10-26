package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Talks to the local model through Spring AI; the rest of the module only knows {@link ChatModelPort}. */
@Component
public class OllamaChatAdapter implements ChatModelPort {

    private final ChatClient chatClient;

    public OllamaChatAdapter(ChatModel chatModel, ChatProperties properties) {
        this.chatClient = ChatClient.builder(chatModel).defaultSystem(properties.systemPrompt()).build();
    }

    /**
     * A failure before any fragment means the model could not be reached or refused the call; one after the first
     * fragment means the answer broke half-way. Both are typed so the caller never sees client exceptions.
     */
    @Override
    public Flux<String> streamAnswer(String message) {
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean();
            return chatClient.prompt()
                    .user(message)
                    .stream()
                    .content()
                    .filter(fragment -> !fragment.isEmpty())
                    .doOnNext(fragment -> emitted.set(true))
                    .onErrorMap(error -> !(error instanceof HelpdeskException),
                            error -> ExceptionUtil.technical(emitted.get()
                                    ? ResponseCodeEnum.CHAT_STREAM_FAILED
                                    : ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, error));
        });
    }
}
