package com.shoplab.helpdesk.retrieval.infrastructure.in.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.ReindexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.config.EmbeddingProperties;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;

class IndexOnStartupRunnerTests {

    private final IndexDocumentsServicePort indexDocuments = mock(IndexDocumentsServicePort.class);
    private final ReindexDocumentsServicePort reindexDocuments = mock(ReindexDocumentsServicePort.class);

    private void startWith(String setting) {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.just(IndexingSummary.empty()));
        when(reindexDocuments.reindex(false)).thenReturn(Mono.just(IndexingSummary.empty()));
        when(reindexDocuments.reindex(true)).thenReturn(Mono.just(IndexingSummary.empty()));
        new IndexOnStartupRunner(indexDocuments, reindexDocuments, new EmbeddingProperties(null, null, null, setting))
                .run(new DefaultApplicationArguments());
    }

    @Test
    void byDefaultItOnlyIndexesWhatAnInterruptedRunLeftPending() {
        startWith("false");

        verify(indexDocuments).indexPending(false);
        verifyNoInteractions(reindexDocuments);
    }

    @Test
    void withTrueItReindexesTheDocumentsOfAnotherModel() {
        startWith("true");

        verify(reindexDocuments).reindex(false);
        verifyNoInteractions(indexDocuments);
    }

    @Test
    void withForceItReindexesEveryDocument() {
        startWith("force");

        verify(reindexDocuments).reindex(true);
        verifyNoInteractions(indexDocuments);
    }

    @Test
    void aFailureNeverStopsTheStartUp() {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.error(new IllegalStateException("model down")));

        new IndexOnStartupRunner(indexDocuments, reindexDocuments, new EmbeddingProperties(null, null, null, null))
                .run(new DefaultApplicationArguments());

        verify(indexDocuments).indexPending(false);
    }
}
