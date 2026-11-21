package com.shoplab.helpdesk.eval.application.usecase;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.eval.application.port.out.AssistantSettingsPort;
import com.shoplab.helpdesk.eval.config.EvalProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReadAssistantSettingsUseCaseTests {

    private final AssistantSettingsPort port = mock(AssistantSettingsPort.class);
    private final ReadAssistantSettingsUseCase useCase = new ReadAssistantSettingsUseCase(
            new EvalAccessGuard(new EvalProperties("tok")), port);

    @Test
    void withoutTheRightTokenTheSettingsAreNotEvenAskedFor() {
        StepVerifier.create(useCase.read("other")).expectError(EvalPersistenceDisabledException.class).verify();

        verifyNoInteractions(port);
    }

    @Test
    void withTheRightTokenTheSettingsOfTheAssistantAreReturned() {
        AssistantSettingsSnapshot snapshot = new AssistantSettingsSnapshot(
                new RetrievalConfig(4, 0.6, 8000, null, null), List.of());
        when(port.current()).thenReturn(Mono.just(snapshot));

        StepVerifier.create(useCase.read("tok")).expectNext(snapshot).verifyComplete();
    }
}
