package com.shoplab.helpdesk.eval.application.usecase;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.config.EvalProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FindEvalRunUseCaseTests {

    private final EvalRunStorePort store = mock(EvalRunStorePort.class);
    private final FindEvalRunUseCase useCase = new FindEvalRunUseCase(new EvalAccessGuard(new EvalProperties("tok")),
            store);

    @Test
    void withoutTheRightTokenTheHistoryIsNotQueried() {
        StepVerifier.create(useCase.find("other", "t031-1")).expectError(EvalPersistenceDisabledException.class)
                .verify();

        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"con espacio", "a/b", "ñandú"})
    void aRunIdThatDoesNotFitIsInvalidAndTheHistoryIsNotQueried(String runId) {
        StepVerifier.create(useCase.find("tok", runId)).expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(store);
    }

    @Test
    void aRunThatIsSavedIsReturned() {
        StoredRunSummary summary = new StoredRunSummary("t031-1", RunKind.QUALITY,
                Instant.parse("2026-11-21T14:02:11Z"), Instant.parse("2026-11-21T14:11:20Z"));
        when(store.find("t031-1")).thenReturn(Mono.just(Optional.of(summary)));

        StepVerifier.create(useCase.find("tok", "t031-1")).expectNext(summary).verifyComplete();
    }

    @Test
    void aRunThatIsNotSavedIsNotFound() {
        when(store.find(anyString())).thenReturn(Mono.just(Optional.empty()));

        StepVerifier.create(useCase.find("tok", "t031-1")).expectError(EvalRunNotFoundException.class).verify();
    }

    @Test
    void aFailedDatabaseReachesTheCallerAsTheTypedError() {
        when(store.find(anyString())).thenReturn(Mono.error(new EvalStoreUnavailableException(
                new IllegalStateException("down"))));

        StepVerifier.create(useCase.find("tok", "t031-1")).expectError(EvalStoreUnavailableException.class).verify();
    }
}
