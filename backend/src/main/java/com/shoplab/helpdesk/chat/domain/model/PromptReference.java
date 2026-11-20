package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptSource;

/**
 * Which prompt, and which version of it, a turn used.
 *
 * @param name    the prompt
 * @param version the number of the version in Langfuse (at least 1); {@code null} when the source is the default text
 * @param source  where the text came from
 */
public record PromptReference(PromptName name, Integer version, PromptSource source) {

    public PromptReference {
        if (name == null || source == null) {
            throw new IllegalArgumentException("A prompt reference needs a name and a source");
        }
        if (source == PromptSource.LANGFUSE && (version == null || version < 1)) {
            throw new IllegalArgumentException("A version of Langfuse is a number from 1, but was " + version);
        }
        if (source == PromptSource.DEFAULT) {
            version = null;
        }
    }

    public static PromptReference langfuse(PromptName name, int version) {
        return new PromptReference(name, version, PromptSource.LANGFUSE);
    }

    public static PromptReference defaultValue(PromptName name) {
        return new PromptReference(name, null, PromptSource.DEFAULT);
    }

    public boolean isDefault() {
        return source == PromptSource.DEFAULT;
    }

    /** How a trace records it: the version number, or {@code default}. */
    public String asTraceValue() {
        return isDefault() ? PromptConstants.DEFAULT_TRACE_VALUE : String.valueOf(version);
    }
}
