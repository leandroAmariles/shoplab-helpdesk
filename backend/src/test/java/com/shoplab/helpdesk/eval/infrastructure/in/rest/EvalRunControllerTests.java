package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.application.port.in.FindEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.in.SaveEvalRunServicePort;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunAlreadyExistsException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(EvalRunController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class EvalRunControllerTests {

    private static final String TOKEN = "controller-token";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SaveEvalRunServicePort save;

    @MockitoBean
    private FindEvalRunServicePort find;

    private WebTestClient.ResponseSpec post(String body, String token) {
        WebTestClient.RequestBodySpec request = webTestClient.post().uri("/api/eval/runs")
                .contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request = (WebTestClient.RequestBodySpec) request.header("X-Eval-Token", token);
        }
        return request.bodyValue(body).exchange();
    }

    private static String json() {
        return EvalRunFixtures.qualityJson("t031-1", 6);
    }

    @Test
    void aSavedRunIsCreatedAndAnswersWhatWasStored() {
        when(save.save(eq(TOKEN), any())).thenReturn(Mono.just(new SavedRun("t031-1", RunKind.QUALITY, 2, 2)));

        post(json(), TOKEN).expectStatus().isCreated().expectBody()
                .jsonPath("$.run_id").isEqualTo("t031-1").jsonPath("$.kind").isEqualTo("QUALITY")
                .jsonPath("$.results").isEqualTo(2).jsonPath("$.new_case_revisions").isEqualTo(2);
    }

    @Test
    void theBodyIsMappedFieldByFieldKeepingEmptyScoresAsEmpty() {
        when(save.save(eq(TOKEN), any())).thenReturn(Mono.just(new SavedRun("t031-1", RunKind.QUALITY, 2, 2)));

        post(json(), TOKEN).expectStatus().isCreated();

        ArgumentCaptor<EvalRun> sent = ArgumentCaptor.forClass(EvalRun.class);
        verify(save).save(eq(TOKEN), sent.capture());
        EvalRun run = sent.getValue();
        assertThat(run.runId()).isEqualTo("t031-1");
        assertThat(run.kind()).isEqualTo(RunKind.QUALITY);
        assertThat(run.startedAt()).isEqualTo(java.time.Instant.parse("2026-11-21T14:02:11Z"));
        assertThat(run.prompts()).hasSize(4).anySatisfy(prompt -> {
            assertThat(prompt.name()).isEqualTo("chat-context");
            assertThat(prompt.version()).isEqualTo(2);
            assertThat(prompt.pinned()).isTrue();
        });
        assertThat(run.judge().underlyingModel()).isEqualTo("ollama_chat/qwen2.5:7b");
        assertThat(run.evaluator().judgmentInstructionsVersion()).isEqualTo("2");
        assertThat(run.retrievalConfig().topK()).isEqualTo(4);
        assertThat(run.results()).hasSize(2);
        assertThat(run.results().get(0).caseKind()).isEqualTo(CaseKind.ANSWERED);
        assertThat(run.results().get(0).evalCase().expectedDocuments()).containsExactly(EvalRunFixtures.DOCUMENT);
        assertThat(run.results().get(1).scores()).containsOnlyKeys("faithfulness")
                .containsEntry("faithfulness", null);
        assertThat(run.results().get(1).traceId()).isNull();
    }

    @Test
    void aMissingTokenReachesTheUseCaseAsNullAndIsForbidden() {
        when(save.save(isNull(), any())).thenReturn(Mono.error(new EvalPersistenceDisabledException()));

        post(json(), null).expectStatus().isForbidden().expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_EVAL_00001");
    }

    @Test
    void aKeyNobodyDefinedIsRefusedAtAnyLevelAndNothingIsSaved() {
        String top = json().replace("\"origin\":\"local\"", "\"origin\":\"local\",\"note\":\"x\"");
        String inResult = json().replace("\"abstained\":false", "\"abstained\":false,\"answer\":\"texto\"");
        String inCase = json().replace("\"category\":\"payments\"", "\"category\":\"payments\",\"fragments\":[]");
        String inPrompt = json().replace("\"pinned\":true", "\"pinned\":true,\"text\":\"el prompt\"");

        for (String body : new String[] {top, inResult, inCase, inPrompt}) {
            post(body, TOKEN).expectStatus().isBadRequest().expectBody().jsonPath("$.code")
                    .isEqualTo("SLHD_GENE_00002");
        }
        verifyNoInteractions(save);
    }

    @Test
    void aBodyThatIsNotJsonIsABadRequest() {
        post("{\"run_id\":", TOKEN).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_GENE_00002");
        verifyNoInteractions(save);
    }

    @Test
    void aRunThatBreaksARuleIsUnprocessableWithTheFieldInTheDetail() {
        when(save.save(any(), any())).thenReturn(Mono.error(new EvalRunInvalidException(
                "results[1].scores.faithfulness: must be between 0 and 1")));

        post(json(), TOKEN).expectStatus().isEqualTo(422).expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_EVAL_00002")
                .jsonPath("$.detail").isEqualTo("results[1].scores.faithfulness: must be between 0 and 1");
    }

    @Test
    void aRunIdThatAlreadyExistsIsAConflict() {
        when(save.save(any(), any())).thenReturn(Mono.error(new EvalRunAlreadyExistsException()));

        post(json(), TOKEN).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_EVAL_00003");
    }

    @Test
    void aDatabaseThatFailsIsServiceUnavailableWithoutItsCause() {
        when(save.save(any(), any())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new IllegalStateException("secret database detail"))));

        post(json(), TOKEN).expectStatus().isEqualTo(503).expectBody(String.class)
                .value(text -> assertThat(text).contains("SLHD_EVAL_00005").doesNotContain("secret database detail"));
    }

    @Test
    void aRunThatIsSavedIsAnsweredWithItsKindAndItsMoments() {
        when(find.find(TOKEN, "t031-1")).thenReturn(Mono.just(new StoredRunSummary("t031-1", RunKind.QUALITY,
                java.time.Instant.parse("2026-11-21T14:02:11Z"), java.time.Instant.parse("2026-11-21T14:11:20Z"))));

        webTestClient.get().uri("/api/eval/runs/t031-1").header("X-Eval-Token", TOKEN).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.run_id").isEqualTo("t031-1").jsonPath("$.kind").isEqualTo("QUALITY")
                .jsonPath("$.started_at").isEqualTo("2026-11-21T14:02:11Z")
                .jsonPath("$.recorded_at").isEqualTo("2026-11-21T14:11:20Z");
    }

    @Test
    void aRunThatIsNotSavedIsNotFound() {
        when(find.find(eq(TOKEN), any())).thenReturn(Mono.error(new EvalRunNotFoundException()));

        webTestClient.get().uri("/api/eval/runs/t031-9").header("X-Eval-Token", TOKEN).exchange()
                .expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("SLHD_EVAL_00004");
    }

    @Test
    void askingForARunWithoutTheTokenIsForbidden() {
        when(find.find(isNull(), any())).thenReturn(Mono.error(new EvalPersistenceDisabledException()));

        webTestClient.get().uri("/api/eval/runs/t031-1").exchange().expectStatus().isForbidden();
    }

    @Test
    void aRunIdThatDoesNotFitIsUnprocessableWithTheFieldInTheDetail() {
        when(find.find(eq(TOKEN), any())).thenReturn(Mono.error(new EvalRunInvalidException("run_id: must match")));

        webTestClient.get().uri("/api/eval/runs/con%20espacio").header("X-Eval-Token", TOKEN).exchange()
                .expectStatus().isEqualTo(422).expectBody().jsonPath("$.detail").isEqualTo("run_id: must match");
    }

    @Test
    void aDatabaseThatFailsWhileLookingIsServiceUnavailable() {
        when(find.find(eq(TOKEN), any())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new IllegalStateException("down"))));

        webTestClient.get().uri("/api/eval/runs/t031-1").header("X-Eval-Token", TOKEN).exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    void noAnswerEverCarriesTheToken() {
        when(save.save(any(), any())).thenReturn(Mono.error(new EvalPersistenceDisabledException()));

        post(json(), TOKEN).expectBody(String.class).value(text -> assertThat(text).doesNotContain(TOKEN));
    }
}
