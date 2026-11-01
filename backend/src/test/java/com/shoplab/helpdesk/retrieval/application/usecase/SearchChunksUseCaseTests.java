package com.shoplab.helpdesk.retrieval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingStorePort;
import com.shoplab.helpdesk.retrieval.config.EmbeddingProperties;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SearchChunksUseCaseTests {

    private static final String MODEL = "bge-m3";
    private static final SearchResult RESULT = new SearchResult(UUID.randomUUID(), UUID.randomUUID(), "politica.md",
            "Politica > Plazos", 1, "Treinta dias.", 0.93);

    private final EmbeddingStorePort store = mock(EmbeddingStorePort.class);
    private final EmbeddingMetricsPort metrics = mock(EmbeddingMetricsPort.class);
    private final DeterministicEmbeddingPort embedding = new DeterministicEmbeddingPort(MODEL);
    private final SearchChunksUseCase useCase = new SearchChunksUseCase(embedding, store, metrics,
            new EmbeddingProperties(16, 5, false, "false"));

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void aBlankQueryIsRejectedWithoutCallingTheModel(String query) {
        StepVerifier.create(useCase.search(query, null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(BusinessException.class);
                    assertThat(((HelpdeskException) error).getResponseCode())
                            .isEqualTo(ResponseCodeEnum.SEARCH_QUERY_INVALID);
                })
                .verify();

        assertThat(embedding.batches()).isZero();
        verify(store, never()).search(any(), anyString(), anyInt());
    }

    @Test
    void embedsTheQueryAndSearchesOnlyWithTheConfiguredModel() {
        when(store.search(any(), eq(MODEL), eq(5))).thenReturn(Mono.just(List.of(RESULT)));

        StepVerifier.create(useCase.search("plazo de devolucion", null))
                .expectNext(List.of(RESULT))
                .verifyComplete();

        verify(store).search(DeterministicEmbeddingPort.vectorFor("plazo de devolucion"), MODEL, 5);
        verify(metrics).recordSearch(any());
    }

    @Test
    void theLimitIsKeptInsideTheAllowedRange() {
        when(store.search(any(), anyString(), anyInt())).thenReturn(Mono.just(List.of(RESULT)));

        useCase.search("q", 0).block();
        useCase.search("q", 7).block();
        useCase.search("q", 500).block();

        verify(store).search(any(), eq(MODEL), eq(1));
        verify(store).search(any(), eq(MODEL), eq(7));
        verify(store).search(any(), eq(MODEL), eq(50));
    }

    @Test
    void withNothingToCompareTheAnswerIsAnEmptyList() {
        when(store.search(any(), anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(store.hasChunksFromOtherModels(MODEL)).thenReturn(Mono.just(false));

        StepVerifier.create(useCase.search("q", null)).expectNext(List.of()).verifyComplete();
    }

    @Test
    void anEmptyAnswerAfterAModelChangeStillAnswersEmptyAndLooksForTheReason() {
        when(store.search(any(), anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(store.hasChunksFromOtherModels(MODEL)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.search("q", null)).expectNext(List.of()).verifyComplete();

        verify(store).hasChunksFromOtherModels(MODEL);
    }

    @Test
    void aFailureOfTheCheckForOtherModelsNeverBreaksTheSearch() {
        when(store.search(any(), anyString(), anyInt())).thenReturn(Mono.just(List.of()));
        when(store.hasChunksFromOtherModels(MODEL)).thenReturn(Mono.error(new IllegalStateException("down")));

        StepVerifier.create(useCase.search("q", null)).expectNext(List.of()).verifyComplete();
    }

    @Test
    void aFailureOfTheModelReachesTheCallerWithoutPartialResults() {
        embedding.failAlways(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE);

        StepVerifier.create(useCase.search("q", null))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE))
                .verify();

        verify(store, never()).search(any(), anyString(), anyInt());
        verify(metrics).recordSearch(any());
    }
}
