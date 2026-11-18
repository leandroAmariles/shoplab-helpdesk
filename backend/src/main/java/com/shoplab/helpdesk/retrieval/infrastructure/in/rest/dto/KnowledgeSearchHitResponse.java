package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto;

/**
 * A chunk found by the search as the REST API publishes it, without the identifiers that only matter inside
 * {@code retrieval}.
 *
 * @param source   file name of the document
 * @param title    readable title of the document
 * @param section  path of headings that contains the chunk, empty when there is none
 * @param position position of the chunk in the document
 * @param score    cosine similarity with the query; the higher, the closer
 * @param text     text of the chunk
 */
public record KnowledgeSearchHitResponse(String source, String title, String section, int position, double score,
        String text) {
}
