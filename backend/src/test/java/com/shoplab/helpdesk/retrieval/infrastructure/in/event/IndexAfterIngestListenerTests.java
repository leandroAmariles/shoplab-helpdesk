package com.shoplab.helpdesk.retrieval.infrastructure.in.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentIngestedEvent;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class IndexAfterIngestListenerTests {

    private static final DocumentIngestedEvent EVENT = new DocumentIngestedEvent(UUID.randomUUID(), "politica.md");

    private final IndexDocumentsServicePort indexDocuments = mock(IndexDocumentsServicePort.class);
    private final IndexAfterIngestListener listener = new IndexAfterIngestListener(indexDocuments,
            Duration.ofMillis(5));

    @Test
    void startsTheIndexingOfThePendingDocumentsWithoutRetryingFailedOnes() {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.just(IndexingSummary.indexed(2)));

        listener.onDocumentIngested(EVENT);

        verify(indexDocuments).indexPending(false);
    }

    @Test
    void doesNotWaitForTheIndexingToFinish() {
        AtomicBoolean subscribed = new AtomicBoolean();
        when(indexDocuments.indexPending(false)).thenReturn(Mono.never().doOnSubscribe(s -> subscribed.set(true))
                .then(Mono.just(IndexingSummary.empty())));

        listener.onDocumentIngested(EVENT);

        assertThat(subscribed).isTrue();
    }

    @Test
    void aFailureIsLoggedAndNeverReachesTheIngestion() {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onDocumentIngested(EVENT);

        verify(indexDocuments).indexPending(false);
    }

    @Test
    void triesAgainWhenAnotherRunWasInProgressAndMayHaveMissedTheDocument() throws InterruptedException {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.just(IndexingSummary.skippedRun()),
                Mono.just(IndexingSummary.indexed(1)));

        listener.onDocumentIngested(EVENT);
        Thread.sleep(200);

        verify(indexDocuments, times(2)).indexPending(false);
    }

    @Test
    void givesUpAfterAFewAttempts() throws InterruptedException {
        when(indexDocuments.indexPending(false)).thenReturn(Mono.just(IndexingSummary.skippedRun()));

        listener.onDocumentIngested(EVENT);
        Thread.sleep(400);

        verify(indexDocuments, times(6)).indexPending(false);
    }
}
