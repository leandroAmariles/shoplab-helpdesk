package com.shoplab.helpdesk.retrieval.domain.model;

import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import java.util.List;

/**
 * A document parsed and chunked by the ingest service.
 *
 * @param name   file name of the document; it identifies the document in the knowledge base
 * @param title  title of the document
 * @param format format of the source file
 * @param chunks chunks in document order
 */
public record ParsedDocument(String name, String title, DocumentFormat format, List<ParsedChunk> chunks) {

    public ParsedDocument {
        chunks = List.copyOf(chunks);
    }
}
