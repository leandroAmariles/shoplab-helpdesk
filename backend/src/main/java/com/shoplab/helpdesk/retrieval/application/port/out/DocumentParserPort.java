package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import reactor.core.publisher.Mono;

public interface DocumentParserPort {

    /** Parses and chunks the document; a rejected document is a business error, a failing service a technical one. */
    Mono<ParsedDocument> parse(String name, byte[] content);
}
