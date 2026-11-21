package com.shoplab.helpdesk.eval.domain.model.enums;

import java.util.Arrays;
import java.util.Optional;

/** Where a prompt text came from: a numbered version of Langfuse, or the default text packaged with the backend. */
public enum PromptSource {
    LANGFUSE,
    DEFAULT;

    public static Optional<PromptSource> fromValue(String value) {
        return Arrays.stream(values()).filter(source -> source.name().equals(value)).findFirst();
    }
}
