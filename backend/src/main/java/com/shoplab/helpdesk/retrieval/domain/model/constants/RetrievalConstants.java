package com.shoplab.helpdesk.retrieval.domain.model.constants;

public final class RetrievalConstants {

    public static final String PARSE_METRIC = "retrieval.ingest.parse";
    public static final String OUTCOME_TAG = "outcome";
    public static final String EMBEDDING_BATCH_METRIC = "retrieval.embedding.batch";
    public static final String EMBEDDING_DOCUMENTS_METRIC = "retrieval.embedding.documents";
    public static final String SEARCH_METRIC = "retrieval.search";

    /** Size of the vectors: the width of {@code retrieval.chunk.embedding} (specs/LAB-19). */
    public static final int EMBEDDING_DIMENSIONS = 1024;
    public static final int MAX_SEARCH_LIMIT = 50;

    private RetrievalConstants() {
    }
}
