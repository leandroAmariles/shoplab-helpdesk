package com.shoplab.helpdesk.retrieval.application.port.in;

import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import reactor.core.publisher.Mono;

public interface IngestDocumentServicePort {

    /**
     * Parses and chunks the document with the ingest service and stores it, replacing what was stored for the same
     * name. Nothing is stored when any step fails; the error is a typed {@code HelpdeskException}.
     */
    Mono<IngestedDocument> ingest(IngestDocumentCommand command);
}
