package com.shoplab.helpdesk.retrieval.application.usecase;

import java.util.ArrayList;
import java.util.List;

/** Splits the work for the model in batches of a fixed size, keeping the order. */
final class EmbeddingBatches {

    private EmbeddingBatches() {
    }

    static <T> List<List<T>> split(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int from = 0; from < items.size(); from += batchSize) {
            batches.add(items.subList(from, Math.min(from + batchSize, items.size())));
        }
        return batches;
    }
}
