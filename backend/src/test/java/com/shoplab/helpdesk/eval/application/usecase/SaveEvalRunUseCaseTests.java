package com.shoplab.helpdesk.eval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.config.EvalProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunAlreadyExistsException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SaveEvalRunUseCaseTests {

    private static final String TOKEN = "test-token";

    private final EvalRunStorePort store = mock(EvalRunStorePort.class);
    private final CaseRevisionHasher hasher = new CaseRevisionHasher();
    private final SaveEvalRunUseCase useCase = new SaveEvalRunUseCase(
            new EvalAccessGuard(new EvalProperties(TOKEN)), new EvalRunValidator(), hasher, store);

    @Test
    void aWrongTokenIsRefusedBeforeTheRunIsLookedAtAndNothingIsStored() {
        EvalRun invalid = EvalRunFixtures.quality("bad id", 6).build();

        StepVerifier.create(useCase.save("wrong", invalid)).expectError(EvalPersistenceDisabledException.class)
                .verify();

        verifyNoInteractions(store);
    }

    @Test
    void anInvalidRunNeverReachesTheStore() {
        EvalRun invalid = EvalRunFixtures.quality("bad id", 6).build();

        StepVerifier.create(useCase.save(TOKEN, invalid)).expectError(EvalRunInvalidException.class).verify();

        verifyNoInteractions(store);
    }

    @Test
    void aValidRunIsStoredWithTheRevisionOfEachCaseAndTheOutcomeIsReturned() {
        EvalRun run = EvalRunFixtures.quality("t031-1", 6).build();
        SavedRun saved = new SavedRun("t031-1", RunKind.QUALITY, 3, 3);
        when(store.save(any())).thenReturn(Mono.just(saved));

        StepVerifier.create(useCase.save(TOKEN, run)).expectNext(saved).verifyComplete();

        ArgumentCaptor<EvalRun> stored = ArgumentCaptor.forClass(EvalRun.class);
        verify(store).save(stored.capture());
        assertThat(stored.getValue().results()).hasSize(3).allSatisfy(result -> assertThat(
                result.evalCase().contentHash()).isEqualTo(hasher.hash(result.evalCase())).hasSize(64));
        assertThat(stored.getValue().runId()).isEqualTo("t031-1");
    }

    @Test
    void aRunThatAlreadyExistsAndAFailedDatabaseReachTheCallerAsTheyAre() {
        EvalRun run = EvalRunFixtures.quality("t031-1", 6).build();
        when(store.save(any())).thenReturn(Mono.error(new EvalRunAlreadyExistsException()),
                Mono.error(new EvalStoreUnavailableException(new IllegalStateException("down"))));

        StepVerifier.create(useCase.save(TOKEN, run)).expectError(EvalRunAlreadyExistsException.class).verify();
        StepVerifier.create(useCase.save(TOKEN, run)).expectError(EvalStoreUnavailableException.class).verify();
    }
}
