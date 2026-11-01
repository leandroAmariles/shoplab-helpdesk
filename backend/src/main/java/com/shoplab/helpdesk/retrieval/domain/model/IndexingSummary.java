package com.shoplab.helpdesk.retrieval.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a run of the embeddings step.
 *
 * @param documents documents that got all their vectors
 * @param chunks    chunks that got their vector
 * @param failed    documents that failed, with the code of the error
 * @param skipped   true when another run was in progress and this one did nothing
 */
public record IndexingSummary(int documents, int chunks, List<IndexingFailure> failed, boolean skipped) {

    public IndexingSummary {
        failed = List.copyOf(failed);
    }

    public static IndexingSummary empty() {
        return new IndexingSummary(0, 0, List.of(), false);
    }

    public static IndexingSummary skippedRun() {
        return new IndexingSummary(0, 0, List.of(), true);
    }

    public static IndexingSummary indexed(int chunks) {
        return new IndexingSummary(1, chunks, List.of(), false);
    }

    public static IndexingSummary failure(String source, String code) {
        return new IndexingSummary(0, 0, List.of(new IndexingFailure(source, code)), false);
    }

    public IndexingSummary plus(IndexingSummary other) {
        List<IndexingFailure> all = new ArrayList<>(failed);
        all.addAll(other.failed);
        return new IndexingSummary(documents + other.documents, chunks + other.chunks, all, skipped || other.skipped);
    }
}
