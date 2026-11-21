package com.shoplab.helpdesk.eval.domain.model.enums;

import java.util.Arrays;
import java.util.Optional;

/** What an evaluation run measured: where the search finds the documents, or how good the answers are. */
public enum RunKind {
    RETRIEVAL,
    QUALITY;

    public static Optional<RunKind> fromValue(String value) {
        return Arrays.stream(values()).filter(kind -> kind.name().equals(value)).findFirst();
    }
}
