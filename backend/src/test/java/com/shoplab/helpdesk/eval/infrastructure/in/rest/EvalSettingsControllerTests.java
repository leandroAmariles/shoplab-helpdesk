package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.in.ReadAssistantSettingsServicePort;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
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

@WebFluxTest(EvalSettingsController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class EvalSettingsControllerTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReadAssistantSettingsServicePort settings;

    @Test
    void theSettingsHaveTheShapeOfTheContract() {
        when(settings.read("tok")).thenReturn(Mono.just(new AssistantSettingsSnapshot(
                new RetrievalConfig(4, 0.6, 8000, null, null), List.of(
                        new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                        new PromptVersion("chat-empty-context", null, PromptSource.DEFAULT, false)))));

        webTestClient.get().uri("/api/eval/settings").header("X-Eval-Token", "tok").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.retrieval.top_k").isEqualTo(4)
                .jsonPath("$.retrieval.similarity_threshold").isEqualTo(0.6)
                .jsonPath("$.retrieval.max_context_characters").isEqualTo(8000)
                .jsonPath("$.prompts[0].name").isEqualTo("chat-system")
                .jsonPath("$.prompts[0].version").isEqualTo(3)
                .jsonPath("$.prompts[0].source").isEqualTo("LANGFUSE")
                .jsonPath("$.prompts[1].version").value(version -> org.assertj.core.api.Assertions.assertThat(version)
                        .isNull())
                .jsonPath("$.prompts[1].source").isEqualTo("DEFAULT");
    }

    @Test
    void withoutAValidTokenItIsForbidden() {
        when(settings.read(isNull())).thenReturn(Mono.error(new EvalPersistenceDisabledException()));
        when(settings.read(any(String.class))).thenReturn(Mono.error(new EvalPersistenceDisabledException()));

        webTestClient.get().uri("/api/eval/settings").exchange().expectStatus().isForbidden().expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_EVAL_00001");
    }
}
