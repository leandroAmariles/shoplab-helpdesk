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
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ReindexDocumentsUseCaseTests {

    private static final String NEW_MODEL = "other-model";

    private final EmbeddingStorePort store = mock(EmbeddingStorePort.class);
    private final EmbeddingMetricsPort metrics = mock(EmbeddingMetricsPort.class);
    private final DeterministicEmbeddingPort embedding = new DeterministicEmbeddingPort(NEW_MODEL);
    private final IndexingGuard guard = new IndexingGuard();
    private final ReindexDocumentsUseCase useCase = new ReindexDocumentsUseCase(new DocumentEmbedder(store,
            embedding, metrics, guard, new EmbeddingProperties(16, 5, false, "false")));

    private void documents(IndexScope scope, PendingDocument... documents) {
        when(store.findDocuments(scope, NEW_MODEL)).thenReturn(Flux.just(documents));
        when(store.saveEmbeddings(any(), anyList(), anyString())).thenReturn(Mono.just(true));
        when(store.markFailed(any(), anyString())).thenReturn(Mono.empty());
    }

    @Test
    void withoutForceItGoesThroughTheDocumentsOfAnotherModelAndStoresTheConfiguredName() {
        PendingDocument stale = IndexDocumentsUseCaseTests.document("old.md", 3);
        documents(IndexScope.OTHER_MODEL, stale);

        IndexingSummary summary = useCase.reindex(false).block();

        assertThat(summary.documents()).isEqualTo(1);
        assertThat(summary.chunks()).isEqualTo(3);
        verify(store).saveEmbeddings(eq(stale), anyList(), eq(NEW_MODEL));
        verify(store, never()).findDocuments(eq(IndexScope.ALL), anyString());
    }

    @Test
    void withForceItGoesThroughEveryDocument() {
        documents(IndexScope.ALL, IndexTestData.document("a.md", 1), IndexTestData.document("b.md", 1));

        IndexingSummary summary = useCase.reindex(true).block();

        assertThat(summary.documents()).isEqualTo(2);
        verify(store, never()).findDocuments(eq(IndexScope.OTHER_MODEL), anyString());
    }

    @Test
    void whenTheNewModelFailsTheDocumentKeepsItsPreviousVectorsAndIsMarked() {
        PendingDocument stale = IndexTestData.document("old.md", 2);
        PendingDocument next = IndexTestData.document("next.md", 1);
        documents(IndexScope.OTHER_MODEL, stale, next);
        embedding.failNext(1, ResponseCodeEnum.EMBEDDING_SERVICE_FAILED);

        IndexingSummary summary = useCase.reindex(false).block();

        assertThat(summary.failed()).extracting("source").containsExactly("old.md");
        assertThat(summary.documents()).isEqualTo(1);
        verify(store, never()).saveEmbeddings(eq(stale), anyList(), anyString());
        verify(store).markFailed(eq(stale), anyString());
        verify(store).saveEmbeddings(eq(next), anyList(), eq(NEW_MODEL));
    }

    @Test
    void itSharesTheTurnWithTheIndexing() {
        assertThat(guard.tryAcquire()).isTrue();

        assertThat(useCase.reindex(false).block().skipped()).isTrue();
        verify(store, never()).findDocuments(any(), anyString());
    }

    @Test
    void whenNothingHasToChangeTheModelIsNotCalled() {
        documents(IndexScope.OTHER_MODEL);

        assertThat(useCase.reindex(false).block()).isEqualTo(IndexingSummary.empty());
        assertThat(embedding.batches()).isZero();
    }
}
