package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.FeedbackStorePort;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackAlreadyPromotedException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackNotNegativeException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackTurnNotFoundException;
import com.shoplab.helpdesk.chat.domain.exception.InvalidGoldenCaseException;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.GoldenCaseDraft;
import com.shoplab.helpdesk.chat.domain.model.PromoteFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class PromoteFeedbackUseCaseTests {

    private static final String TURN_ID = "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9";
    private static final String CONV_ID = "9c3a2011-7e51-4a0e-88a4-1a1c80f7c111";
    private static final Instant NOW = Instant.parse("2026-11-23T15:10:00Z");

    private final FeedbackStorePort store = mock(FeedbackStorePort.class);
    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final GoldenCaseValidator validator = new GoldenCaseValidator();
    private final PromoteFeedbackUseCase useCase = new PromoteFeedbackUseCase(store, validator, metrics);

    private static GoldenCaseDraft validDraft() {
        return new GoldenCaseDraft(
                "G121",
                "¿Cuántos días tengo para devolver una compra?",
                "Puedes solicitar la devolución dentro de los 30 días naturales siguientes a la fecha de entrega.",
                List.of("returns-01-return-policy"),
                "returns");
    }

    @Test
    void validatesDraftPersistsPromotionAndRecordsMetricWithoutLoggingFreeText(CapturedOutput output) {
        GoldenCaseDraft draft = validDraft();
        String expectedJsonl = validator.validateAndSerialize(draft);
        FeedbackItem promoted = new FeedbackItem(
                TURN_ID, CONV_ID, draft.question(), "Respuesta corta", List.of("returns-01-return-policy"),
                null, FeedbackRating.DOWN, "Incompleta", NOW, NOW, "G121", expectedJsonl, NOW);
        when(store.promote(TURN_ID, "G121", expectedJsonl)).thenReturn(Mono.just(promoted));

        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand(TURN_ID, draft)))
                .expectNext(promoted)
                .verifyComplete();

        verify(store).promote(TURN_ID, "G121", expectedJsonl);
        verify(metrics).recordFeedbackPromoted();
        assertThat(output.getOut())
                .contains("caseId=G121")
                .contains("category=returns")
                .doesNotContain("¿Cuántos días")
                .doesNotContain(TURN_ID);
    }

    @Test
    void rejectsAnInvalidTurnIdBeforeValidatingOrCallingStore() {
        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand("not-a-uuid", validDraft())))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();

        verifyNoInteractions(store, metrics);
    }

    @Test
    void rejectsAnInvalidGoldenCaseBeforeCallingStore() {
        GoldenCaseDraft invalid = new GoldenCaseDraft(
                "G121",
                "¿Tienen tienda en Madrid?",
                "Abrimos en 2025 en la calle mayor.",
                List.of(),
                "off_topic");

        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand(TURN_ID, invalid)))
                .expectError(InvalidGoldenCaseException.class)
                .verify();

        verifyNoInteractions(store, metrics);
    }

    @Test
    void propagatesStoreExceptionsWithoutIncrementingPromotedMetric() {
        when(store.promote(any(), any(), any())).thenReturn(Mono.error(new FeedbackTurnNotFoundException()));
        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand(TURN_ID, validDraft())))
                .expectError(FeedbackTurnNotFoundException.class)
                .verify();

        when(store.promote(any(), any(), any())).thenReturn(Mono.error(new FeedbackNotNegativeException()));
        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand(TURN_ID, validDraft())))
                .expectError(FeedbackNotNegativeException.class)
                .verify();

        when(store.promote(any(), any(), any())).thenReturn(Mono.error(new FeedbackAlreadyPromotedException()));
        StepVerifier.create(useCase.promote(new PromoteFeedbackCommand(TURN_ID, validDraft())))
                .expectError(FeedbackAlreadyPromotedException.class)
                .verify();

        verify(metrics, never()).recordFeedbackPromoted();
    }
}
