package com.shoplab.helpdesk.retrieval.application.port.in;

import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import reactor.core.publisher.Flux;

public interface ListDocumentsServicePort {

    /** Every document of the knowledge base with its state and chunk count, the most recently ingested first. */
    Flux<DocumentSummary> list();
}
