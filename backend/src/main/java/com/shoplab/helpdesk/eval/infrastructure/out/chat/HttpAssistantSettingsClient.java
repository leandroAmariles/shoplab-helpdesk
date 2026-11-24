package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import com.shoplab.helpdesk.eval.application.port.out.AssistantSettingsPort;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive HTTP client for {@code GET /api/assistant/settings} on {@code chat}.
 * Enabled via {@code shoplab.chat.settings.client.mode=http} for service extraction (LAB-106 / LAB-130).
 */
@Component
@ConditionalOnProperty(name = "shoplab.chat.settings.client.mode", havingValue = "http")
public class HttpAssistantSettingsClient implements AssistantSettingsPort {

    private final WebClient webClient;

    @Autowired
    public HttpAssistantSettingsClient(
            WebClient.Builder webClientBuilder,
            @Value("${shoplab.chat.settings.client.base-url:http://localhost:8080}") String baseUrl) {
        this(webClientBuilder.baseUrl(baseUrl).build());
    }

    public HttpAssistantSettingsClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<AssistantSettingsSnapshot> current() {
        return webClient.get()
                .uri("/api/assistant/settings")
                .retrieve()
                .bodyToMono(AssistantSettingsApiResponse.class)
                .map(HttpAssistantSettingsClient::toSnapshot);
    }

    private static AssistantSettingsSnapshot toSnapshot(AssistantSettingsApiResponse response) {
        List<PromptVersion> prompts = response.prompts() == null
                ? List.of()
                : response.prompts().stream().map(HttpAssistantSettingsClient::toPrompt).toList();
        return new AssistantSettingsSnapshot(
                new RetrievalConfig(
                        response.topK(),
                        response.similarityThreshold(),
                        response.maxContextCharacters(),
                        null,
                        null),
                prompts);
    }

    private static PromptVersion toPrompt(ServedPromptApiResponse prompt) {
        PromptSource source = PromptSource.fromValue(prompt.source())
                .orElseThrow(() -> new IllegalStateException("The chat published an unknown prompt source"));
        return new PromptVersion(prompt.name(), prompt.version(), source, false);
    }

    private record AssistantSettingsApiResponse(
            int topK,
            double similarityThreshold,
            int maxContextCharacters,
            List<ServedPromptApiResponse> prompts) {
    }

    private record ServedPromptApiResponse(String name, Integer version, String source) {
    }
}
