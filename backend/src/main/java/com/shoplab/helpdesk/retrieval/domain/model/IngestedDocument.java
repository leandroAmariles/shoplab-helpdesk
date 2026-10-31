package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.UUID;

/**
 * Result of an ingestion: the document as it is now stored.
 *
 * @param documentId identifier of the document; it stays the same when the same source is ingested again
 * @param source     file name that identifies the document
 * @param title      title of the document
 * @param chunkCount number of chunks stored for it
 */
public record IngestedDocument(UUID documentId, String source, String title, int chunkCount) {
}
