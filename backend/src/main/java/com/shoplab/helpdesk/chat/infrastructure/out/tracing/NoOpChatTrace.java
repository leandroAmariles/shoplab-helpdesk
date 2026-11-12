package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Used when the tracing is off (the default): runs the turn as it is and records nothing. */
public class NoOpChatTrace implements ChatTracePort {

    @Override
    public Flux<ChatEvent> traceTurn(StreamChatCommand command, Function<TurnTrace, Flux<ChatEvent>> turn) {
        return Flux.defer(() -> turn.apply(TurnTrace.NONE));
    }
}
