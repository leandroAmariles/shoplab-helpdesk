package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/** How the context of an answer turned out; its lower-case name is the {@code outcome} tag of the metric. */
public enum ContextOutcome {
    WITH_CONTEXT,
    NO_CONTEXT,
    FAILED;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
