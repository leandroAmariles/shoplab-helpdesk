package com.shoplab.helpdesk.retrieval.domain.model.enums;

/** Which documents a run of the embeddings step goes through. */
public enum IndexScope {
    /** Documents waiting for their vectors. */
    PENDING,
    /** Pending documents and those whose last attempt failed (a retry without ingesting again). */
    RETRY,
    /** Pending and failed documents, and those with a chunk from another model or without vector. */
    OTHER_MODEL,
    /** Every document, whatever its state (a forced reindexing). */
    ALL
}
