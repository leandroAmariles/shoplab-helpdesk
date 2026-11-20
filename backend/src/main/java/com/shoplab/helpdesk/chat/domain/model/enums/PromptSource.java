package com.shoplab.helpdesk.chat.domain.model.enums;

/** Where the text of a prompt used by a turn came from. */
public enum PromptSource {

    /** A version loaded from Langfuse. */
    LANGFUSE,
    /** The default text of the repository: Langfuse had no valid version to give. */
    DEFAULT
}
