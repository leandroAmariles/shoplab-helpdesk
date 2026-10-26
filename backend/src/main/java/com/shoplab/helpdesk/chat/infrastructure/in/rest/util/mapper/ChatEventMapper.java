package com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.ChatEventPayload;
import org.springframework.http.codec.ServerSentEvent;

public final class ChatEventMapper {

    private ChatEventMapper() {
    }

    public static ServerSentEvent<Object> toServerSentEvent(ChatEvent event) {
        return switch (event) {
            case TokenEvent token -> build(ChatConstants.TOKEN_EVENT, new ChatEventPayload.Token(token.content()));
            case DoneEvent done -> build(ChatConstants.DONE_EVENT, new ChatEventPayload.Done(done.fragments()));
            case ErrorEvent error -> build(ChatConstants.ERROR_EVENT,
                    new ChatEventPayload.Error(error.code(), error.message()));
        };
    }

    private static ServerSentEvent<Object> build(String name, Object payload) {
        return ServerSentEvent.builder().event(name).data(payload).build();
    }
}
