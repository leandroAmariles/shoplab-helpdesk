package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import reactor.core.publisher.Flux;


public interface DocumentCatalogPort {

    Flux<DocumentSummary> listDocuments();
}
