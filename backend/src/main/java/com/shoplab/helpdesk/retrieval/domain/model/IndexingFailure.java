package com.shoplab.helpdesk.retrieval.domain.model;

/** A document that could not be indexed; only the error code travels, never the text of the document. */
public record IndexingFailure(String source, String code) {
}
