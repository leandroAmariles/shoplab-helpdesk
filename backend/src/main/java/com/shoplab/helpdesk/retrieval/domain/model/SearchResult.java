package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.UUID;

/**
 * A chunk found by the similarity search.
 *
 * @param chunkId    identifier of the chunk
 * @param documentId identifier of its document
 * @param source     file name of the document
 * @param title      readable title of the document
 * @param section    path of headings that contains the chunk, empty when there is none
 * @param position   position of the chunk in the document
 * @param text       text of the chunk
 * @param score      cosine similarity with the query ({@code 1 - distance}); the higher, the closer
 */
public record SearchResult(UUID chunkId, UUID documentId, String source, String title, String section, int position,
        String text, double score) {
}
