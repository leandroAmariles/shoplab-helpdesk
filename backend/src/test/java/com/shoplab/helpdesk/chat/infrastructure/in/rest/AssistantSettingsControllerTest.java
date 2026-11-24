package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import com.shoplab.helpdesk.chat.application.port.in.GetAssistantSettingsServicePort;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(AssistantSettingsController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class AssistantSettingsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GetAssistantSettingsServicePort settingsPort;

    @Test
    void returns200WithRetrievalParametersAndServedPrompts() {
        AssistantSettings settings = new AssistantSettings(5, 0.45, 6000, List.of(
                new ServedPrompt("chat-system", 3, "LANGFUSE"),
                new ServedPrompt("chat-small-talk", null, "DEFAULT")));
        when(settingsPort.current()).thenReturn(Mono.just(settings));

        webTestClient.get().uri("/api/assistant/settings")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.topK").isEqualTo(5)
                .jsonPath("$.similarityThreshold").isEqualTo(0.45)
                .jsonPath("$.maxContextCharacters").isEqualTo(6000)
                .jsonPath("$.prompts.length()").isEqualTo(2)
                .jsonPath("$.prompts[0].name").isEqualTo("chat-system")
                .jsonPath("$.prompts[0].version").isEqualTo(3)
                .jsonPath("$.prompts[0].source").isEqualTo("LANGFUSE")
                .jsonPath("$.prompts[1].name").isEqualTo("chat-small-talk")
                .jsonPath("$.prompts[1].version").value(v -> assertThat(v).isNull())
                .jsonPath("$.prompts[1].source").isEqualTo("DEFAULT");
    }

    @Test
    void unexpectedFailureMapsTo500ErrorResponse() {
        when(settingsPort.current()).thenReturn(Mono.error(new IllegalStateException("unexpected")));

        webTestClient.get().uri("/api/assistant/settings")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_GENE_00001")
                .jsonPath("$.message").isEqualTo("Unexpected error");
    }
}
