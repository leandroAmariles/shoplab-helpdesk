package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/** What was being done with the conversation memory; its lower-case name is the {@code operation} tag. */
public enum MemoryOperation {
    READ,
    WRITE;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
