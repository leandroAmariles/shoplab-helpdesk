package com.shoplab.helpdesk.eval.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.in.GetEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsServicePort;
import com.shoplab.helpdesk.eval.application.usecase.TraceUrlBuilder;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.OriginCount;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.RunListItem;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** The read endpoints of the history: no token, snake_case, and errors with the project contract (specs/LAB-32). */
@WebFluxTest(EvalHistoryController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class EvalHistoryControllerTests {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ListEvalRunsServicePort list;

    @MockitoBean
    private GetEvalRunServicePort get;

    @MockitoBean
    private TraceUrlBuilder trace;

    private static RunListing listing() {
        Map<String, MetricStat> metrics = new LinkedHashMap<>();
        metrics.put("faithfulness", new MetricStat(0.7134, 96));
        metrics.put("context_precision", new MetricStat(null, 0));
        RunListItem item = new RunListItem("t031-1", RunKind.QUALITY, Instant.parse("2026-11-21T14:02:11Z"),
                Instant.parse("2026-11-21T14:11:03Z"), "dev", null, "chat",
                List.of(new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                        new PromptVersion("chat-empty-context", null, PromptSource.DEFAULT, false)),
                96, metrics);
        return new RunListing(RunKind.QUALITY, List.of(item), 3, 5,
                List.of(new OriginCount("dev", 3), new OriginCount("local", 2)));
    }

    @Test
    void theListIsAnsweredWithoutAnyTokenAndWithTheContractFields() {
        when(list.list(any())).thenReturn(Mono.just(listing()));

        webTestClient.get().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.kind").isEqualTo("QUALITY")
                .jsonPath("$.shown").isEqualTo(1)
                .jsonPath("$.total").isEqualTo(3)
                .jsonPath("$.total_all_origins").isEqualTo(5)
                .jsonPath("$.origins[0].origin").isEqualTo("dev")
                .jsonPath("$.origins[0].count").isEqualTo(3)
                .jsonPath("$.runs[0].run_id").isEqualTo("t031-1")
                .jsonPath("$.runs[0].kind").isEqualTo("QUALITY")
                .jsonPath("$.runs[0].started_at").isEqualTo("2026-11-21T14:02:11Z")
                .jsonPath("$.runs[0].finished_at").isEqualTo("2026-11-21T14:11:03Z")
                .jsonPath("$.runs[0].origin").isEqualTo("dev")
                .jsonPath("$.runs[0].commit_sha").value(value -> assertThat(value).isNull())
                .jsonPath("$.runs[0].assistant_model").isEqualTo("chat")
                .jsonPath("$.runs[0].cases_measured").isEqualTo(96)
                .jsonPath("$.runs[0].prompts[0].name").isEqualTo("chat-system")
                .jsonPath("$.runs[0].prompts[0].version").isEqualTo(3)
                .jsonPath("$.runs[0].prompts[0].source").isEqualTo("LANGFUSE")
                .jsonPath("$.runs[0].prompts[0].pinned").isEqualTo(false)
                .jsonPath("$.runs[0].prompts[1].version").value(value -> assertThat(value).isNull())
                .jsonPath("$.runs[0].metrics.faithfulness.mean").isEqualTo(0.7134)
                .jsonPath("$.runs[0].metrics.faithfulness.n").isEqualTo(96);
    }

    @Test
    void aMetricNobodyCouldComputeComesAsNullWithZeroCasesNeverAsZero() {
        when(list.list(any())).thenReturn(Mono.just(listing()));

        webTestClient.get().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("\"context_precision\":{\"mean\":null,\"n\":0}"));
    }

    @Test
    void theFieldsOfTheListAreExactlyTheOnesOfTheContract() {
        when(list.list(any())).thenReturn(Mono.just(listing()));

        webTestClient.get().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("\"kind\"", "\"shown\"", "\"total\"", "\"total_all_origins\"",
                            "\"origins\"", "\"runs\"");
                    assertThat(body).doesNotContain("token", "Token", "answer", "question", "fragment");
                });
    }

    @Test
    void theParametersOfTheRequestReachTheUseCaseAsTheyCame() {
        when(list.list(any())).thenReturn(Mono.just(listing()));

        webTestClient.get().uri("/api/eval/history/runs?kind=RETRIEVAL&origin=dev&limit=25").exchange()
                .expectStatus().isOk();

        ArgumentCaptor<ListEvalRunsCommand> command = ArgumentCaptor.forClass(ListEvalRunsCommand.class);
        verify(list).list(command.capture());
        assertThat(command.getValue()).isEqualTo(new ListEvalRunsCommand("RETRIEVAL", "dev", "25"));
    }

    @Test
    void aRequestWithoutAKindIsAnUnprocessableRunWithTheNameOfTheParameter() {
        when(list.list(any())).thenReturn(Mono.error(new EvalRunInvalidException("kind: is required")));

        webTestClient.get().uri("/api/eval/history/runs").exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_EVAL_00002")
                .jsonPath("$.detail").isEqualTo("kind: is required");
    }

    @Test
    void aLimitThatIsNotValidIsAnUnprocessableRun() {
        when(list.list(any()))
                .thenReturn(Mono.error(new EvalRunInvalidException("limit: must be a whole number from 1 to 500")));

        webTestClient.get().uri("/api/eval/history/runs?kind=QUALITY&limit=0").exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_EVAL_00002");
    }

    @Test
    void aDatabaseThatDoesNotAnswerIsAServiceUnavailableWithoutItsDetails() {
        when(list.list(any())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new RuntimeException("secret database detail"))));

        webTestClient.get().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(text -> assertThat(text)
                        .contains("SLHD_EVAL_00005").doesNotContain("secret database detail"));
    }

    @Test
    void theHistoryCannotBeWrittenByTheReadingEndpoints() {
        webTestClient.post().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectStatus().is4xxClientError();
        webTestClient.delete().uri("/api/eval/history/runs?kind=QUALITY").exchange()
                .expectStatus().is4xxClientError();
    }

    private static StoredRun storedRun(RunKind kind) {
        Instant at = Instant.parse("2026-11-21T14:02:11Z");
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("faithfulness", 0.2);
        scores.put("answer_relevancy", null);
        StoredResult answered = new StoredResult("G044", "c".repeat(64), "returns", "¿Cuántos días tengo?",
                "Tienes 30 días naturales.", List.of("politica-devoluciones"), CaseKind.ANSWERED, scores,
                List.of("politica-envios", "politica-devoluciones"), false, TRACE);
        StoredResult withoutTrace = new StoredResult("G045", "d".repeat(64), "returns", "¿Y si ya lo usé?",
                "Aplican condiciones.", List.of(), CaseKind.UNANSWERED, Map.of("faithfulness", 1.0), List.of(), true,
                null);
        boolean quality = kind == RunKind.QUALITY;
        return new StoredRun("t031-1", kind, at, at.plusSeconds(530), at.plusSeconds(531), "dev", null,
                "9".repeat(64), 96, 24, null, "nomic-embed-text",
                quality ? List.of(new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, true)) : List.of(),
                quality ? new JudgeInfo("judge", "qwen3:8b", 0.0, 7, 6000) : null,
                quality ? new EvaluatorInfo("ragas", "0.3.5", "2") : null,
                quality ? new RetrievalConfig(4, 0.6, 8000, null, null)
                        : new RetrievalConfig(4, 0.6, 8000, 50, List.of(1, 3, 5)),
                List.of(new ScopeSummary("all", 96, Map.of("faithfulness", new MetricStat(0.7134, 96))),
                        new ScopeSummary("returns", 18, Map.of("faithfulness", new MetricStat(null, 0)))),
                List.of(answered, withoutTrace));
    }

    @Test
    void aRunIsReadWithItsParametersItsSummaryAndItsResultsInSnakeCaseAndWithoutAnyToken() {
        when(get.get("t031-1")).thenReturn(Mono.just(storedRun(RunKind.QUALITY)));
        when(trace.urlFor(TRACE)).thenReturn(Optional.of("http://localhost:3001/trace/" + TRACE));

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.run.run_id").isEqualTo("t031-1")
                .jsonPath("$.run.kind").isEqualTo("QUALITY")
                .jsonPath("$.run.started_at").isEqualTo("2026-11-21T14:02:11Z")
                .jsonPath("$.run.finished_at").isEqualTo("2026-11-21T14:11:01Z")
                .jsonPath("$.run.recorded_at").isEqualTo("2026-11-21T14:11:02Z")
                .jsonPath("$.run.origin").isEqualTo("dev")
                .jsonPath("$.run.commit_sha").value(value -> assertThat(value).isNull())
                .jsonPath("$.run.dataset_hash").isEqualTo("9".repeat(64))
                .jsonPath("$.run.cases_measured").isEqualTo(96)
                .jsonPath("$.run.cases_excluded").isEqualTo(24)
                .jsonPath("$.run.assistant_model").value(value -> assertThat(value).isNull())
                .jsonPath("$.run.embedding_model").isEqualTo("nomic-embed-text")
                .jsonPath("$.run.prompts[0].pinned").isEqualTo(true)
                .jsonPath("$.run.judge.model").isEqualTo("judge")
                .jsonPath("$.run.judge.underlying_model").isEqualTo("qwen3:8b")
                .jsonPath("$.run.judge.temperature").isEqualTo(0.0)
                .jsonPath("$.run.judge.seed").isEqualTo(7)
                .jsonPath("$.run.judge.max_input_chars").isEqualTo(6000)
                .jsonPath("$.run.evaluator.library").isEqualTo("ragas")
                .jsonPath("$.run.evaluator.library_version").isEqualTo("0.3.5")
                .jsonPath("$.run.evaluator.judgment_instructions_version").isEqualTo("2")
                .jsonPath("$.run.retrieval_config.top_k").isEqualTo(4)
                .jsonPath("$.run.retrieval_config.similarity_threshold").isEqualTo(0.6)
                .jsonPath("$.run.retrieval_config.max_context_characters").isEqualTo(8000)
                .jsonPath("$.summary[0].scope").isEqualTo("all")
                .jsonPath("$.summary[0].cases").isEqualTo(96)
                .jsonPath("$.summary[0].metrics.faithfulness.mean").isEqualTo(0.7134)
                .jsonPath("$.summary[1].metrics.faithfulness.n").isEqualTo(0)
                .jsonPath("$.results[0].case_id").isEqualTo("G044")
                .jsonPath("$.results[0].revision").isEqualTo("c".repeat(64))
                .jsonPath("$.results[0].category").isEqualTo("returns")
                .jsonPath("$.results[0].question").isEqualTo("¿Cuántos días tengo?")
                .jsonPath("$.results[0].reference_answer").isEqualTo("Tienes 30 días naturales.")
                .jsonPath("$.results[0].expected_documents[0]").isEqualTo("politica-devoluciones")
                .jsonPath("$.results[0].case_kind").isEqualTo("answered")
                .jsonPath("$.results[0].scores.faithfulness").isEqualTo(0.2)
                .jsonPath("$.results[0].scores.answer_relevancy").value(value -> assertThat(value).isNull())
                .jsonPath("$.results[0].retrieved_documents[1]").isEqualTo("politica-devoluciones")
                .jsonPath("$.results[0].abstained").isEqualTo(false)
                .jsonPath("$.results[0].trace_id").isEqualTo(TRACE)
                .jsonPath("$.results[0].trace_url").isEqualTo("http://localhost:3001/trace/" + TRACE);
    }

    @Test
    void aScoreWithoutValueIsNullInTheBodyAndNeverAZero() {
        when(get.get("t031-1")).thenReturn(Mono.just(storedRun(RunKind.QUALITY)));
        when(trace.urlFor(any())).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectBody(String.class).value(body -> assertThat(body)
                        .contains("\"answer_relevancy\":null")
                        .contains("\"faithfulness\":{\"mean\":null,\"n\":0}"));
    }

    @Test
    void withoutATemplateOrATraceThereIsNoTraceUrl() {
        when(get.get("t031-1")).thenReturn(Mono.just(storedRun(RunKind.QUALITY)));
        when(trace.urlFor(any())).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectBody()
                .jsonPath("$.results[0].trace_id").isEqualTo(TRACE)
                .jsonPath("$.results[0].trace_url").value(value -> assertThat(value).isNull())
                .jsonPath("$.results[1].trace_id").value(value -> assertThat(value).isNull())
                .jsonPath("$.results[1].trace_url").value(value -> assertThat(value).isNull());
    }

    @Test
    void aRetrievalRunHasNoJudgeAndKeepsTheDepthAndTheKs() {
        when(get.get("t031-1")).thenReturn(Mono.just(storedRun(RunKind.RETRIEVAL)));
        when(trace.urlFor(any())).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectBody()
                .jsonPath("$.run.kind").isEqualTo("RETRIEVAL")
                .jsonPath("$.run.judge").value(value -> assertThat(value).isNull())
                .jsonPath("$.run.evaluator").value(value -> assertThat(value).isNull())
                .jsonPath("$.run.retrieval_config.depth").isEqualTo(50)
                .jsonPath("$.run.retrieval_config.ks[2]").isEqualTo(5);
    }

    @Test
    void aRunThatDoesNotExistIsNotFound() {
        when(get.get("nope")).thenReturn(Mono.error(new EvalRunNotFoundException()));

        webTestClient.get().uri("/api/eval/history/runs/nope").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_EVAL_00004");
    }

    @Test
    void aRunIdThatDoesNotFitIsAnUnprocessableRun() {
        when(get.get("a_b")).thenReturn(Mono.error(new EvalRunInvalidException("run_id: must match x")));

        webTestClient.get().uri("/api/eval/history/runs/a_b").exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_EVAL_00002").jsonPath("$.detail").isNotEmpty();
    }

    @Test
    void theDetailOfARunWhoseDatabaseFailsIsServiceUnavailable() {
        when(get.get("t031-1")).thenReturn(Mono.error(new EvalStoreUnavailableException(new RuntimeException("x"))));

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_EVAL_00005");
    }

    @Test
    void theBodyOfARunNeverHasTheTextOfAnAnswerOrOfAFragmentNorAToken() {
        when(get.get("t031-1")).thenReturn(Mono.just(storedRun(RunKind.QUALITY)));
        when(trace.urlFor(any())).thenReturn(Optional.empty());

        webTestClient.get().uri("/api/eval/history/runs/t031-1").exchange()
                .expectBody(String.class).value(body -> assertThat(body.toLowerCase())
                        .doesNotContain("token", "\"answer\"", "\"response\"", "\"fragment", "\"chunk"));
    }
}
