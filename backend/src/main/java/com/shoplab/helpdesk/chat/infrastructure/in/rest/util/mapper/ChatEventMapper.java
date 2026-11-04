package com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.chat.domain.model.AnswerSource;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.ChatEventPayload;
import org.springframework.http.codec.ServerSentEvent;

public final class ChatEventMapper {

    private ChatEventMapper() {
    }

    public static ServerSentEvent<Object> toServerSentEvent(ChatEvent event) {
        return switch (event) {
            case SourcesEvent sources -> build(ChatConstants.SOURCES_EVENT, new ChatEventPayload.Sources(
                    sources.sources().stream().map(ChatEventMapper::toSource).toList()));
            case TokenEvent token -> build(ChatConstants.TOKEN_EVENT, new ChatEventPayload.Token(token.content()));
            case DoneEvent done -> build(ChatConstants.DONE_EVENT, new ChatEventPayload.Done(done.fragments()));
            case ErrorEvent error -> build(ChatConstants.ERROR_EVENT,
                    new ChatEventPayload.Error(error.code(), error.message()));
        };
    }

    private static ChatEventPayload.Source toSource(AnswerSource source) {
        return new ChatEventPayload.Source(source.number(), source.title(), source.section(), source.text());
    }

    private static ServerSentEvent<Object> build(String name, Object payload) {
        return ServerSentEvent.builder().event(name).data(payload).build();
    }
}
