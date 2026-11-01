package com.shoplab.helpdesk.chat.domain.model.enums;

/** What the chat does when no fragment of the knowledge base is useful for the question. */
public enum NoContextMode {
    /** The answer is a configured message and the chat model is not called. */
    FIXED_MESSAGE,
    /** The chat model is called with the empty-context template and answers by itself. */
    MODEL
}
