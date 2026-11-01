package com.shoplab.helpdesk.retrieval.application.port.in;

import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import reactor.core.publisher.Mono;

public interface IndexDocumentsServicePort {

    /**
     * Computes and stores the vectors of the pending documents, which then become indexed. With {@code retryFailed}
     * the documents whose last attempt failed are tried again too. A document that fails is marked as such and does
     * not stop the others; one already indexed is not computed again. When another run is in progress this one does
     * nothing and the summary says so.
     */
    Mono<IndexingSummary> indexPending(boolean retryFailed);
}
