package com.shoplab.helpdesk.eval.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ListEvalRunsUseCaseTests {

    private final EvalHistoryPort history = mock(EvalHistoryPort.class);
    private final ListEvalRunsUseCase useCase = new ListEvalRunsUseCase(new EvalHistoryQueryValidator(), history);

    private static RunListing empty(RunKind kind) {
        return new RunListing(kind, List.of(), 0, 0, List.of());
    }

    @Test
    void theListIsReadWithTheDefaultLimitAndAllOriginsWhenTheRequestOnlyHasAKind() {
        when(history.list(RunKind.QUALITY, null, 100)).thenReturn(Mono.just(empty(RunKind.QUALITY)));

        StepVerifier.create(useCase.list(new ListEvalRunsCommand("QUALITY", null, null)))
                .expectNext(empty(RunKind.QUALITY)).verifyComplete();

        verify(history).list(RunKind.QUALITY, null, 100);
    }

    @Test
    void theOriginAndTheLimitAreHandedOverAsTheyWereAsked() {
        when(history.list(eq(RunKind.RETRIEVAL), eq("dev"), eq(5))).thenReturn(Mono.just(empty(RunKind.RETRIEVAL)));

        StepVerifier.create(useCase.list(new ListEvalRunsCommand("RETRIEVAL", "dev", "5")))
                .expectNext(empty(RunKind.RETRIEVAL)).verifyComplete();
    }

    @Test
    void aRequestThatIsNotValidNeverReachesTheHistory() {
        StepVerifier.create(useCase.list(new ListEvalRunsCommand("OTHER", null, null)))
                .expectError(EvalRunInvalidException.class).verify();
        StepVerifier.create(useCase.list(new ListEvalRunsCommand("QUALITY", null, "501")))
                .expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(history);
    }

    @Test
    void readingNeedsNoTokenSoItWorksWhileSavingIsOff() {
        // The use case has no guard and no properties: nothing about the token of saving can stop a read.
        when(history.list(any(), isNull(), anyInt())).thenReturn(Mono.just(empty(RunKind.QUALITY)));

        StepVerifier.create(useCase.list(new ListEvalRunsCommand("QUALITY", null, null)))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void aFailureOfTheHistoryIsPassedOn() {
        when(history.list(any(), any(), anyInt()))
                .thenReturn(Mono.error(new EvalStoreUnavailableException(new RuntimeException("down"))));

        StepVerifier.create(useCase.list(new ListEvalRunsCommand("QUALITY", null, null)))
                .expectError(EvalStoreUnavailableException.class).verify();
    }
}
