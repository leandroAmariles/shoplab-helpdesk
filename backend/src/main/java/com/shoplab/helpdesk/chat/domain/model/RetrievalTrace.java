package com.shoplab.helpdesk.chat.domain.model;

import java.util.List;

/**
 * What the search for context did in one turn.
 *
 * @param searchText the text actually searched (the question plus earlier questions when the memory adds them)
 * @param candidates fragments found before selecting (never negative)
 * @param delivered  the fragments handed to the model, in the order of the citations
 * @param failed     the search itself failed
 */
public record RetrievalTrace(String searchText, int candidates, List<ContextFragment> delivered, boolean failed) {

    public RetrievalTrace {
        if (searchText == null) {
            throw new IllegalArgumentException("A retrieval trace needs the text that was searched");
        }
        if (candidates < 0) {
            throw new IllegalArgumentException("The candidates of a retrieval must not be negative");
        }
        delivered = delivered == null ? List.of() : List.copyOf(delivered);
    }
}
