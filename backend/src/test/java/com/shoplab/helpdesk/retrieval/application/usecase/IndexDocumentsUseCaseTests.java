package com.shoplab.helpdesk.retrieval.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingStorePort;
import com.shoplab.helpdesk.retrieval.config.EmbeddingProperties;
import com.shoplab.helpdesk.retrieval.domain.model.ChunkEmbedding;
import com.shoplab.helpdesk.retrieval.domain.model.ChunkToEmbed;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexOutcome;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IndexDocumentsUseCaseTests {

    private static final String MODEL = "bge-m3";

    private final EmbeddingStorePort store = mock(EmbeddingStorePort.class);
    private final EmbeddingMetricsPort metrics = mock(EmbeddingMetricsPort.class);
    private final DeterministicEmbeddingPort embedding = new DeterministicEmbeddingPort(MODEL);
    private final IndexingGuard guard = new IndexingGuard();
    private final IndexDocumentsUseCase useCase = new IndexDocumentsUseCase(new DocumentEmbedder(store, embedding,
            metrics, guard, new EmbeddingProperties(2, 5, false, "false")));

    static PendingDocument document(String source, int chunks) {
        List<ChunkToEmbed> items = IntStream.range(0, chunks)
                .mapToObj(index -> new ChunkToEmbed(UUID.randomUUID(), index, source + " chunk " + index)).toList();
        return new PendingDocument(UUID.randomUUID(), source, Instant.parse("2026-10-30T10:00:00Z"), items);
    }

    private void documents(IndexScope scope, PendingDocument... documents) {
        when(store.findDocuments(scope, MODEL)).thenReturn(Flux.just(documents));
    }

    private void storing(boolean saved) {
        when(store.saveEmbeddings(any(), anyList(), anyString())).thenReturn(Mono.just(saved));
        when(store.markFailed(any(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void computesTheVectorsInBatchesAndStoresThemTogetherWithTheModelName() {
        PendingDocument pending = document("politica.md", 5);
        documents(IndexScope.PENDING, pending);
        storing(true);

        StepVerifier.create(useCase.indexPending(false))
                .assertNext(summary -> {
                    assertThat(summary.documents()).isEqualTo(1);
                    assertThat(summary.chunks()).isEqualTo(5);
                    assertThat(summary.failed()).isEmpty();
                    assertThat(summary.skipped()).isFalse();
                })
                .verifyComplete();

        assertThat(embedding.batches()).isEqualTo(3);
        ArgumentCaptor<List<ChunkEmbedding>> stored = ArgumentCaptor.captor();
        verify(store).saveEmbeddings(eq(pending), stored.capture(), eq(MODEL));
        assertThat(stored.getValue()).extracting(ChunkEmbedding::chunkId)
                .containsExactlyElementsOf(pending.chunks().stream().map(ChunkToEmbed::chunkId).toList());
        assertThat(stored.getValue().get(0).vector())
                .isEqualTo(DeterministicEmbeddingPort.vectorFor("politica.md chunk 0"));
        verify(metrics).recordDocument(IndexOutcome.INDEXED);
        verify(metrics, org.mockito.Mockito.times(3)).recordBatch(eq(IndexOutcome.INDEXED), any());
    }

    @Test
    void aFailureOfTheModelMarksTheDocumentAndStoresNoVector() {
        PendingDocument pending = document("politica.md", 3);
        documents(IndexScope.PENDING, pending);
        storing(true);
        embedding.failAlways(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE);

        StepVerifier.create(useCase.indexPending(false))
                .assertNext(summary -> {
                    assertThat(summary.documents()).isZero();
                    assertThat(summary.failed()).singleElement().satisfies(failure -> {
                        assertThat(failure.source()).isEqualTo("politica.md");
                        assertThat(failure.code()).isEqualTo("SLHD_RETR_00006");
                    });
                })
                .verifyComplete();

        verify(store, never()).saveEmbeddings(any(), anyList(), anyString());
        verify(store).markFailed(pending, ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE.getDefaultMessage());
        verify(metrics).recordDocument(IndexOutcome.FAILED);
        verify(metrics).recordBatch(eq(IndexOutcome.FAILED), any());
    }

    @Test
    void theReasonKeptForTheFailureNeverCarriesTheTextOfTheDocument() {
        PendingDocument pending = document("secreto.md", 1);
        documents(IndexScope.PENDING, pending);
        storing(true);
        embedding.failAlways(ResponseCodeEnum.EMBEDDING_SERVICE_FAILED);

        useCase.indexPending(false).block();

        ArgumentCaptor<String> reason = ArgumentCaptor.captor();
        verify(store).markFailed(eq(pending), reason.capture());
        assertThat(reason.getValue()).isEqualTo("The embedding model failed").doesNotContain("secreto");
    }

    @Test
    void aFailingDocumentDoesNotStopTheOthers() {
        PendingDocument first = document("a.md", 1);
        PendingDocument second = document("b.md", 2);
        documents(IndexScope.PENDING, first, second);
        storing(true);
        embedding.failNext(1, ResponseCodeEnum.EMBEDDING_SERVICE_FAILED);

        StepVerifier.create(useCase.indexPending(false))
                .assertNext(summary -> {
                    assertThat(summary.documents()).isEqualTo(1);
                    assertThat(summary.chunks()).isEqualTo(2);
                    assertThat(summary.failed()).extracting("source").containsExactly("a.md");
                })
                .verifyComplete();

        verify(store).markFailed(eq(first), anyString());
        verify(store).saveEmbeddings(eq(second), anyList(), eq(MODEL));
    }

    @Test
    void retryingFailedDocumentsWidensTheScope() {
        documents(IndexScope.PENDING);
        documents(IndexScope.RETRY, document("failed.md", 1));
        storing(true);

        assertThat(useCase.indexPending(false).block().documents()).isZero();
        assertThat(useCase.indexPending(true).block().documents()).isEqualTo(1);
    }

    @Test
    void withNothingPendingTheModelIsNotCalled() {
        documents(IndexScope.PENDING);

        IndexingSummary summary = useCase.indexPending(false).block();

        assertThat(summary).isEqualTo(IndexingSummary.empty());
        assertThat(embedding.batches()).isZero();
    }

    @Test
    void aDocumentWithoutChunksIsLeftAlone() {
        documents(IndexScope.PENDING, document("empty.md", 0));
        storing(true);

        IndexingSummary summary = useCase.indexPending(false).block();

        assertThat(summary.documents()).isZero();
        assertThat(embedding.batches()).isZero();
        verify(store, never()).saveEmbeddings(any(), anyList(), anyString());
        verify(metrics).recordDocument(IndexOutcome.SKIPPED);
    }

    @Test
    void vectorsOfADocumentThatChangedMeanwhileAreDiscardedWithoutMarkingAnError() {
        documents(IndexScope.PENDING, document("politica.md", 1));
        storing(false);

        IndexingSummary summary = useCase.indexPending(false).block();

        assertThat(summary.documents()).isZero();
        assertThat(summary.failed()).isEmpty();
        verify(store, never()).markFailed(any(), anyString());
        verify(metrics).recordDocument(IndexOutcome.SKIPPED);
    }

    @Test
    void aRunThatStartsWhileAnotherIsInProgressDoesNothing() {
        assertThat(guard.tryAcquire()).isTrue();

        IndexingSummary summary = useCase.indexPending(false).block();

        assertThat(summary.skipped()).isTrue();
        verify(store, never()).findDocuments(any(), anyString());
        assertThat(embedding.batches()).isZero();
    }

    @Test
    void theTurnIsReleasedEvenWhenTheDatabaseFails() {
        documents(IndexScope.PENDING, document("politica.md", 1));
        when(store.saveEmbeddings(any(), anyList(), anyString())).thenReturn(Mono.error(
                ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, new IllegalStateException())));

        StepVerifier.create(useCase.indexPending(false)).expectError().verify();

        assertThat(guard.tryAcquire()).isTrue();
        verify(store, never()).markFailed(any(), anyString());
    }

    @Test
    void aModelThatReturnsTheWrongNumberOfVectorsFailsTheDocument() {
        PendingDocument pending = document("politica.md", 2);
        documents(IndexScope.PENDING, pending);
        storing(true);
        var shortModel = new DeterministicEmbeddingPort(MODEL) {
            @Override
            public Mono<List<float[]>> embed(List<String> input) {
                return Mono.just(List.of(vectorFor("x")));
            }
        };
        IndexDocumentsUseCase shortUseCase = new IndexDocumentsUseCase(new DocumentEmbedder(store, shortModel,
                metrics, guard, new EmbeddingProperties(2, 5, false, "false")));

        IndexingSummary summary = shortUseCase.indexPending(false).block();

        assertThat(summary.failed()).extracting("code").containsExactly("SLHD_RETR_00007");
        verify(store).markFailed(eq(pending), anyString());
    }
}
