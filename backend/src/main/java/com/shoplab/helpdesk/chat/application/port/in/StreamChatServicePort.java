package com.shoplab.helpdesk.chat.application.port.in;

import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import reactor.core.publisher.Flux;

public interface StreamChatServicePort {

    /**
     * Streams the answer to the customer's message. A failure before the first event is signalled as an error of the
     * returned flux; a failure after it becomes a final {@code ErrorEvent}.
     */
    Flux<ChatEvent> stream(StreamChatCommand command);
}
