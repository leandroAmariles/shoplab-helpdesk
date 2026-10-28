package com.shoplab.helpdesk.retrieval.domain.model;

/**
 * Order to ingest a document.
 *
 * @param name    file name of the document, without any path
 * @param content bytes of the file
 */
public record IngestDocumentCommand(String name, byte[] content) {
}
