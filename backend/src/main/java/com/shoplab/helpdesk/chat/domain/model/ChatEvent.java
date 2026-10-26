package com.shoplab.helpdesk.chat.domain.model;

/** One element of the answer stream: zero or more tokens, then exactly one done or error. */
public sealed interface ChatEvent {

    /** A fragment of the answer, in generation order. */
    record TokenEvent(String content) implements ChatEvent {
    }

    /** The answer finished correctly after {@code fragments} token events. */
    record DoneEvent(int fragments) implements ChatEvent {
    }

    /** The answer failed after the stream had started. */
    record ErrorEvent(String code, String message) implements ChatEvent {
    }
}
