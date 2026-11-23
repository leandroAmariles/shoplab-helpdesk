package com.shoplab.helpdesk.eval.application.usecase;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetEvalRunUseCaseTests {

    private final EvalHistoryPort history = mock(EvalHistoryPort.class);
    private final GetEvalRunUseCase useCase = new GetEvalRunUseCase(new EvalHistoryQueryValidator(), history);

    private static StoredRun run(String runId) {
        Instant at = Instant.parse("2026-11-21T14:02:11Z");
        return new StoredRun(runId, RunKind.RETRIEVAL, at, at.plusSeconds(60), at.plusSeconds(61), "local", null,
                "d".repeat(64), 0, 0, null, "bge-m3", List.of(), null, null,
                new RetrievalConfig(4, 0.6, 8000, 50, List.of(1, 3, 5)), List.of(), List.of());
    }

    @Test
    void aRunThatIsSavedIsReturnedWithoutAnyToken() {
        StoredRun stored = run("t031-1");
        when(history.find("t031-1")).thenReturn(Mono.just(Optional.of(stored)));

        StepVerifier.create(useCase.get("t031-1")).expectNext(stored).verifyComplete();
    }

    @Test
    void aRunThatIsNotSavedIsNotFound() {
        when(history.find(anyString())).thenReturn(Mono.just(Optional.empty()));

        StepVerifier.create(useCase.get("t031-1")).expectError(EvalRunNotFoundException.class).verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"con espacio", "a/b", "ñandú"})
    void aRunIdThatDoesNotFitIsInvalidAndTheHistoryIsNotQueried(String runId) {
        StepVerifier.create(useCase.get(runId)).expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(history);
    }

    @Test
    void aFailureOfTheHistoryIsPassedOn() {
        when(history.find(anyString())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new RuntimeException("down"))));

        StepVerifier.create(useCase.get("t031-1")).expectError(EvalStoreUnavailableException.class).verify();
    }
}
