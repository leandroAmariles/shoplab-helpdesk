package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Records what happens in each chat turn so a developer can inspect it afterwards (LAB-64). It must never change what
 * the customer receives: the events that come out are the ones the turn produced, and any failure of the recording
 * itself is swallowed.
 */
public interface ChatTracePort {

    /**
     * Opens the trace of one turn, runs it and closes the trace when the turn completes, fails or is cancelled. The
     * {@link TurnTrace} handed to {@code turn} is also published in the Reactor context under
     * {@code TracingConstants.TURN_TRACE_KEY}, so the model adapter can report the generation without a new
     * parameter on its port.
     */
    Flux<ChatEvent> traceTurn(StreamChatCommand command, Function<TurnTrace, Flux<ChatEvent>> turn);
}
