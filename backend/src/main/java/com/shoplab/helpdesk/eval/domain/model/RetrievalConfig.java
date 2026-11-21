package com.shoplab.helpdesk.eval.domain.model;

import java.util.List;

/**
 * How the assistant retrieves context. {@code depth} and {@code ks} are only present in a retrieval measurement.
 */
public record RetrievalConfig(Integer topK, Double similarityThreshold, Integer maxContextCharacters, Integer depth,
        List<Integer> ks) {
}
