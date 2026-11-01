package com.shoplab.helpdesk.retrieval.infrastructure.in.event;

import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentIngestedEvent;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Starts the embeddings step when a document was just ingested, without making the ingestion wait for it or fail
 * with it. If another run is in progress (it may not have seen this document) it tries again a few times.
 */
@Component
@ConditionalOnProperty(name = "shoplab.retrieval.embedding.auto-index", havingValue = "true", matchIfMissing = true)
public class IndexAfterIngestListener {

    private static final Logger LOG = LoggerFactory.getLogger(IndexAfterIngestListener.class);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 6;

    private final IndexDocumentsServicePort indexDocuments;
    private final Duration retryDelay;

    @Autowired
    public IndexAfterIngestListener(IndexDocumentsServicePort indexDocuments) {
        this(indexDocuments, DEFAULT_RETRY_DELAY);
    }

    IndexAfterIngestListener(IndexDocumentsServicePort indexDocuments, Duration retryDelay) {
        this.indexDocuments = indexDocuments;
        this.retryDelay = retryDelay;
    }

    @EventListener
    public void onDocumentIngested(DocumentIngestedEvent event) {
        attempt(MAX_ATTEMPTS).subscribe(
                summary -> LOG.debug("Indexing after ingestion finished: source={}, documents={}", event.source(),
                        summary.documents()),
                error -> LOG.warn("Indexing after ingestion failed: source={}, error={}", event.source(),
                        error.toString()));
    }

    private Mono<IndexingSummary> attempt(int attemptsLeft) {
        return indexDocuments.indexPending(false).flatMap(summary -> {
            if (summary.skipped() && attemptsLeft > 1) {
                return Mono.delay(retryDelay).then(Mono.defer(() -> attempt(attemptsLeft - 1)));
            }
            return Mono.just(summary);
        });
    }
}
