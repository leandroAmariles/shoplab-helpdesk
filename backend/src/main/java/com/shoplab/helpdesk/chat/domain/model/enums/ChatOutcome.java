package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/** How an answer ended; its lower-case name is the {@code outcome} tag of the response metric. */
public enum ChatOutcome {
    COMPLETED,
    ERROR,
    CANCELLED;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
