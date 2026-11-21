package com.shoplab.helpdesk.eval.domain.model.enums;

import java.util.Arrays;
import java.util.Optional;

/** The kind of a measured case: it decides which metrics it gets. Conversation cases are never measured or saved. */
public enum CaseKind {
    ANSWERED("answered"),
    UNANSWERED("unanswered");

    private final String value;

    CaseKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<CaseKind> fromValue(String text) {
        return Arrays.stream(values()).filter(kind -> kind.value.equals(text)).findFirst();
    }
}
