package com.shoplab.helpdesk.retrieval.domain.model.enums;

import java.util.Locale;

/** How the embeddings of a batch or a document ended; its lower-case name is the {@code outcome} tag of the metrics. */
public enum IndexOutcome {
    INDEXED,
    FAILED,
    SKIPPED;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
