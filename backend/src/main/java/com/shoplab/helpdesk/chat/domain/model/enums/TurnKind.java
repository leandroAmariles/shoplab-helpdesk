package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Locale;

/**
 * What kind of turn the chat attended (LAB-114); its lower-case name is the {@code kind} tag of the turns metric. A
 * turn whose retrieval fails has no kind: it is counted as a failed retrieval (LAB-22).
 */
public enum TurnKind {
    /** The message was only a courtesy and was answered without retrieval. */
    SMALL_TALK,
    /** A question answered with at least one fragment of context. */
    WITH_CONTEXT,
    /** A question without useful context: the fixed message or, in {@code model} mode, the model by itself. */
    NO_CONTEXT;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
