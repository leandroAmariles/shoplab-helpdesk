package com.shoplab.helpdesk.retrieval.api;

/**
 * A chunk of the knowledge base as other modules see it: where it comes from, what it says and how close it is.
 *
 * @param source   file name of the document
 * @param section  path of headings that contains the chunk, empty when there is none
 * @param position position of the chunk in the document
 * @param text     text of the chunk
 * @param score    cosine similarity with the query; the higher, the closer
 */
public record KnowledgeChunk(String source, String section, int position, String text, double score) {
}
