package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import com.shoplab.contracts.chat.client.AssistantSettingsApi;
import com.shoplab.contracts.chat.model.AssistantSettingsResponse;
import com.shoplab.contracts.chat.model.ServedPromptResponse;
import com.shoplab.helpdesk.eval.application.port.out.AssistantSettingsPort;
import com.shoplab.helpdesk.eval.config.ChatSettingsClientProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reactive HTTP client for {@code GET /api/assistant/settings} on {@code chat-service} (LAB-59).
 * Enabled via {@code shoplab.chat.settings.client.mode=http} during expand-and-contract cutover.
 */
@Component
@ConditionalOnProperty(name = "shoplab.chat.settings.client.mode", havingValue = "http")
public class HttpAssistantSettingsClient implements AssistantSettingsPort {

    private final AssistantSettingsApi settingsApi;
    private final Duration timeout;

    @Autowired
    public HttpAssistantSettingsClient(AssistantSettingsApi settingsApi, ChatSettingsClientProperties properties) {
        this(settingsApi, properties.timeout());
    }

    public HttpAssistantSettingsClient(AssistantSettingsApi settingsApi, Duration timeout) {
        this.settingsApi = settingsApi;
        this.timeout = timeout;
    }

    @Override
    public Mono<AssistantSettingsSnapshot> current() {
        return Mono.defer(settingsApi::getAssistantSettings)
                .timeout(timeout)
                .flatMap(response -> Mono.justOrEmpty(response.getBody()))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("Chat settings answered without a body")))
                .map(HttpAssistantSettingsClient::toSnapshot)
                .onErrorMap(EvalStoreUnavailableException::new);
    }

    private static AssistantSettingsSnapshot toSnapshot(AssistantSettingsResponse response) {
        List<PromptVersion> prompts = response.getPrompts().stream()
                .map(HttpAssistantSettingsClient::toPrompt)
                .toList();
        return new AssistantSettingsSnapshot(
                new RetrievalConfig(
                        response.getTopK(),
                        response.getSimilarityThreshold(),
                        response.getMaxContextCharacters(),
                        null,
                        null),
                prompts);
    }

    private static PromptVersion toPrompt(ServedPromptResponse prompt) {
        PromptSource source = PromptSource.fromValue(prompt.getSource().getValue())
                .orElseThrow(() -> new IllegalStateException("The chat published an unknown prompt source"));
        return new PromptVersion(prompt.getName(), prompt.getVersion(), source, false);
    }
}
