package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/** How a turn ended, as its trace shows it. It is not {@link ChatOutcome}, which is the tag of a metric. */
public enum TurnResult {
    COMPLETED,
    NO_CONTEXT,
    CANCELLED,
    ERROR;

    public String traceValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
