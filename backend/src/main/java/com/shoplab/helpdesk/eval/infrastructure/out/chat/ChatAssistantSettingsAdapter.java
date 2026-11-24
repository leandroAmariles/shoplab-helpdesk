package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import com.shoplab.helpdesk.eval.application.port.out.AssistantSettingsPort;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.AssistantSettingsApi;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Reads the settings of the assistant through what the chat module publishes, never through its internals. */
@Component
@ConditionalOnProperty(name = "shoplab.chat.settings.client.mode", havingValue = "in-process", matchIfMissing = true)
public class ChatAssistantSettingsAdapter implements AssistantSettingsPort {

    private final AssistantSettingsApi chat;

    public ChatAssistantSettingsAdapter(AssistantSettingsApi chat) {
        this.chat = chat;
    }

    @Override
    public Mono<AssistantSettingsSnapshot> current() {
        return Mono.fromSupplier(chat::current).map(ChatAssistantSettingsAdapter::snapshot);
    }

    private static AssistantSettingsSnapshot snapshot(AssistantSettings settings) {
        return new AssistantSettingsSnapshot(
                new RetrievalConfig(settings.topK(), settings.similarityThreshold(), settings.maxContextCharacters(),
                        null, null),
                settings.prompts().stream().map(ChatAssistantSettingsAdapter::prompt).toList());
    }

    private static PromptVersion prompt(ServedPrompt served) {
        return new PromptVersion(served.name(), served.version(), PromptSource.fromValue(served.source())
                .orElseThrow(() -> new IllegalStateException("The chat published an unknown prompt source")), false);
    }
}
