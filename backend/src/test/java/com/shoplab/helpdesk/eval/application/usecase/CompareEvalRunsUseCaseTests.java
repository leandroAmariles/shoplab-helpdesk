package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.in.CompareEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunsNotComparableException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import com.shoplab.helpdesk.shared.exception.PublicDetail;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CompareEvalRunsUseCaseTests {

    private final EvalHistoryPort history = mock(EvalHistoryPort.class);
    private final CompareEvalRunsUseCase useCase = new CompareEvalRunsUseCase(new EvalHistoryQueryValidator(),
            history);

    private static StoredRun run(String runId, RunKind kind, String metric) {
        Instant at = Instant.parse("2026-11-21T14:02:11Z");
        return new StoredRun(runId, kind, at, at.plusSeconds(60), at.plusSeconds(61), "dev", null, "d".repeat(64), 0,
                0, null, "bge-m3", List.of(), null, null, new RetrievalConfig(4, 0.6, 8000, null, null),
                List.of(new ScopeSummary("all", 0, Map.of(metric, new MetricStat(0.5, 1)))), List.of());
    }

    private void saved(StoredRun... runs) {
        for (StoredRun run : runs) {
            when(history.find(run.runId())).thenReturn(Mono.just(Optional.of(run)));
        }
    }

    @Test
    void twoRunsOfTheSameKindAreComparedWithTheDefaultToleranceAndWithoutAnyToken() {
        saved(run("a", RunKind.QUALITY, "faithfulness"), run("b", RunKind.QUALITY, "faithfulness"));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", null)))
                .assertNext(comparison -> {
                    assertThat(comparison.base().runId()).isEqualTo("a");
                    assertThat(comparison.target().runId()).isEqualTo("b");
                    assertThat(comparison.drop()).isEqualByComparingTo("0.05");
                }).verifyComplete();
    }

    @Test
    void theGivenToleranceIsUsed() {
        saved(run("a", RunKind.QUALITY, "faithfulness"), run("b", RunKind.QUALITY, "faithfulness"));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", "0.2")))
                .assertNext(comparison -> assertThat(comparison.drop()).isEqualByComparingTo("0.2"))
                .verifyComplete();
    }

    @Test
    void theSameRunTwiceIsInvalidAndNothingIsRead() {
        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "a", "faithfulness", null)))
                .expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(history);
    }

    @Test
    void invalidParametersAreCheckedBeforeAnyRunIsRead() {
        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "Faithfulness", null)))
                .expectError(EvalRunInvalidException.class).verify();
        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", "2")))
                .expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(history);
    }

    @Test
    void aMetricThatNeitherRunHasIsInvalidInsteadOfAnEmptyComparison() {
        saved(run("a", RunKind.QUALITY, "faithfulness"), run("b", RunKind.QUALITY, "faithfulness"));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "reciprocal_rank", null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(EvalRunInvalidException.class)
                        .extracting(e -> ((PublicDetail) e).publicDetail()).asString().startsWith("metric"))
                .verify();
    }

    @Test
    void aMetricOfOnlyOneRunIsEnough() {
        saved(run("a", RunKind.QUALITY, "faithfulness"), run("b", RunKind.QUALITY, "answer_relevancy"));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "answer_relevancy", null)))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void runsOfDifferentKindsAreNotComparable() {
        saved(run("a", RunKind.QUALITY, "faithfulness"), run("b", RunKind.RETRIEVAL, "reciprocal_rank"));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", null)))
                .expectError(EvalRunsNotComparableException.class).verify();
    }

    @Test
    void aRunThatIsNotSavedIsNotFoundWhicheverOfTheTwoIsMissing() {
        saved(run("a", RunKind.QUALITY, "faithfulness"));
        when(history.find("b")).thenReturn(Mono.just(Optional.empty()));
        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", null)))
                .expectError(EvalRunNotFoundException.class).verify();

        when(history.find("c")).thenReturn(Mono.just(Optional.empty()));
        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("c", "a", "faithfulness", null)))
                .expectError(EvalRunNotFoundException.class).verify();
    }

    @Test
    void aFailureOfTheHistoryIsPassedOn() {
        when(history.find(anyString())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new RuntimeException("down"))));

        StepVerifier.create(useCase.compare(new CompareEvalRunsCommand("a", "b", "faithfulness", null)))
                .expectError(EvalStoreUnavailableException.class).verify();
    }
}
