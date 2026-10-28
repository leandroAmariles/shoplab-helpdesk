package com.shoplab.helpdesk.retrieval.domain.model;

/**
 * A fragment of a document as the ingest service returns it: no identity and no vector yet.
 *
 * @param position consecutive position in the document, from 0
 * @param text     text of the fragment
 * @param section  chain of headings that contains it, separated by " > "; empty when there is none
 */
public record ParsedChunk(int position, String text, String section) {
}
