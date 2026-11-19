package com.shoplab.helpdesk.retrieval.domain.model;

/**
 * Order to ingest a document.
 *
 * @param name      file name of the document, without any path
 * @param content   bytes of the file
 * @param requestId correlation id of the request that asked for it, for the logs; {@code null} when there is none
 */
public record IngestDocumentCommand(String name, byte[] content, String requestId) {

    public IngestDocumentCommand(String name, byte[] content) {
        this(name, content, null);
    }
}
