package com.shoplab.helpdesk.retrieval.domain.model.enums;

import java.util.Locale;

/** How a call to the ingest service ended; its lower-case name is the {@code outcome} tag of the parse metric. */
public enum IngestOutcome {
    OK,
    REJECTED,
    FAILED;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
