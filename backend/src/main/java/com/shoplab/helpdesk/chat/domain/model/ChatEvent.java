package com.shoplab.helpdesk.chat.domain.model;

import java.util.List;

/**
 * One element of the answer stream: the sources when there is context, zero or more tokens, then exactly one done or
 * error.
 */
public sealed interface ChatEvent {

    /** The fragments the answer is generated from, sent once before the first token; only when there is context. */
    record SourcesEvent(List<AnswerSource> sources) implements ChatEvent {

        public SourcesEvent {
            if (sources == null || sources.isEmpty()) {
                throw new IllegalArgumentException("A sources event needs at least one source");
            }
            sources = List.copyOf(sources);
        }
    }

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
