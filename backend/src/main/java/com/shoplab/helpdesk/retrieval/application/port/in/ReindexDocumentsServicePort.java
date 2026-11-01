package com.shoplab.helpdesk.retrieval.application.port.in;

import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import reactor.core.publisher.Mono;

public interface ReindexDocumentsServicePort {

    /**
     * Computes again the vectors of the documents that have chunks from another model than the configured one (or no
     * vector); with {@code force}, of every document. It never calls the ingest service and never changes the text,
     * the position or the metadata of a chunk. A document keeps its previous vectors until all the new ones are
     * stored, so it never mixes two models.
     */
    Mono<IndexingSummary> reindex(boolean force);
}
