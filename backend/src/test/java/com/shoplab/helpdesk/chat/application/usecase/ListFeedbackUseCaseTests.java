package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.application.port.out.FeedbackStorePort;
import com.shoplab.helpdesk.chat.config.FeedbackProperties;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ListFeedbackUseCaseTests {

    private final FeedbackStorePort store = mock(FeedbackStorePort.class);
    private final ListFeedbackUseCase useCase = new ListFeedbackUseCase(store, new FeedbackProperties(120));

    @Test
    void defaultsNullRatingToDownAndDelegatesWithConfiguredBaseCaseNumber() {
        ListFeedbackQuery expectedQuery = new ListFeedbackQuery(FeedbackRating.DOWN, false, 100);
        FeedbackListing listing = new FeedbackListing(1, 1, 0, "G121", List.of());
        when(store.list(expectedQuery, 120)).thenReturn(Mono.just(listing));

        StepVerifier.create(useCase.list(new ListFeedbackQuery(null, false, 100)))
                .expectNext(listing)
                .verifyComplete();

        verify(store).list(expectedQuery, 120);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501})
    void rejectsLimitOutsideOneToFiveHundred(int invalidLimit) {
        StepVerifier.create(useCase.list(new ListFeedbackQuery(FeedbackRating.DOWN, null, invalidLimit)))
                .expectErrorSatisfies(error -> assertThat(((BusinessException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_FEEDBACK))
                .verify();

        verifyNoInteractions(store);
    }
}
