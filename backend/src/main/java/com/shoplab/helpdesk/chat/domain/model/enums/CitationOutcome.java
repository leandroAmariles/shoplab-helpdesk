package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/** How well an answer with context cites its sources; its lower-case name is the {@code outcome} tag of the metric. */
public enum CitationOutcome {
    /** At least one citation and every one points at a source the answer was given. */
    VALID,
    /** Some citation points at a source that does not exist. */
    INVALID,
    /** No citation at all. */
    NONE;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
