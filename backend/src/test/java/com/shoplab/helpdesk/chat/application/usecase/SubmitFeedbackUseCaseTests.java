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
import com.shoplab.helpdesk.chat.domain.exception.FeedbackTurnNotFoundException;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class SubmitFeedbackUseCaseTests {

    private static final String TURN_ID = "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9";
    private static final Instant NOW = Instant.parse("2026-11-23T15:10:00Z");

    private final FeedbackStorePort store = mock(FeedbackStorePort.class);
    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final SubmitFeedbackUseCase useCase = new SubmitFeedbackUseCase(store, metrics);

    @Test
    void normalizesTurnIdAndCommentDelegatesToStoreAndRecordsMetric(CapturedOutput output) {
        FeedbackRecord stored = new FeedbackRecord(
                TURN_ID, FeedbackRating.DOWN, "No menciona el plazo", NOW, NOW, null, null);
        when(store.upsert(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, "No menciona el plazo")))
                .thenReturn(Mono.just(stored));

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(
                        "  " + TURN_ID.toUpperCase() + "  ",
                        FeedbackRating.DOWN,
                        "   No menciona el plazo   ")))
                .expectNext(stored)
                .verifyComplete();

        verify(store).upsert(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, "No menciona el plazo"));
        verify(metrics).recordFeedback(FeedbackRating.DOWN);
        assertThat(output.getOut()).contains("rating=down").contains("hasComment=true")
                .doesNotContain("No menciona el plazo").doesNotContain(TURN_ID);
    }

    @Test
    void convertsBlankOrNullCommentToNull() {
        FeedbackRecord stored = new FeedbackRecord(TURN_ID, FeedbackRating.UP, null, NOW, NOW, null, null);
        when(store.upsert(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.UP, null)))
                .thenReturn(Mono.just(stored));

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.UP, "   \n\t  ")))
                .expectNext(stored)
                .verifyComplete();

        verify(store).upsert(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.UP, null));
        verify(metrics).recordFeedback(FeedbackRating.UP);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-uuid", "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a"})
    void rejectsAnInvalidTurnId(String invalidTurnId) {
        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(invalidTurnId, FeedbackRating.UP, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();

        verifyNoInteractions(store, metrics);
    }

    @Test
    void rejectsAMissingCommandOrMissingRating() {
        StepVerifier.create(useCase.submit(null))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();
        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, null, null)))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();

        verifyNoInteractions(store, metrics);
    }

    @Test
    void rejectsACommentLongerThan500CharactersAfterTrimming() {
        String tooLong = "  " + "a".repeat(501) + "  ";

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, tooLong)))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();

        verifyNoInteractions(store, metrics);
    }

    @Test
    void acceptsACommentOfExactly500Characters() {
        String exact = "a".repeat(500);
        FeedbackRecord stored = new FeedbackRecord(TURN_ID, FeedbackRating.DOWN, exact, NOW, NOW, null, null);
        when(store.upsert(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, exact)))
                .thenReturn(Mono.just(stored));

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, exact)))
                .expectNext(stored)
                .verifyComplete();
    }

    @Test
    void propagatesNotFoundAndAlreadyPromotedErrorsWithoutRecordingMetric() {
        when(store.upsert(any())).thenReturn(Mono.error(new FeedbackTurnNotFoundException()));

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.UP, null)))
                .expectError(FeedbackTurnNotFoundException.class)
                .verify();

        when(store.upsert(any())).thenReturn(Mono.error(new FeedbackAlreadyPromotedException()));

        StepVerifier.create(useCase.submit(new SubmitFeedbackCommand(TURN_ID, FeedbackRating.DOWN, null)))
                .expectError(FeedbackAlreadyPromotedException.class)
                .verify();

        verify(metrics, never()).recordFeedback(any());
    }
}
