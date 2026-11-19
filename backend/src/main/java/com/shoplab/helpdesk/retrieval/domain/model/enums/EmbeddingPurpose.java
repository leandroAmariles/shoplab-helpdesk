package com.shoplab.helpdesk.retrieval.domain.model.enums;

/**
 * Why a text is embedded. The model gateway logs the cost of an embeddings call under this operation, because there is
 * no conversation to attribute it to (LAB-84, US5 scenario 4).
 */
public enum EmbeddingPurpose {

    /** Chunks of a document being indexed (upload or reindexing). */
    DOCUMENT_INDEX("purpose:document-index"),
    /** The question of a search, to find the closest chunks. */
    SEARCH_QUERY("purpose:search-query");

    /** What is sent to the gateway as the {@code user} of the call. */
    private final String userValue;

    EmbeddingPurpose(String userValue) {
        this.userValue = userValue;
    }

    public String getUserValue() {
        return userValue;
    }
}
