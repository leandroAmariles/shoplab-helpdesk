package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.ChatRequest;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper.ChatEventMapper;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final StreamChatServicePort streamChat;

    public ChatController(StreamChatServicePort streamChat) {
        this.streamChat = streamChat;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> chat(@RequestBody ChatRequest request,
            @RequestAttribute(name = RequestIdFilter.REQUEST_ATTRIBUTE, required = false) String requestId) {
        StreamChatCommand command = new StreamChatCommand(request.message(),
                requestId == null ? UUID.randomUUID().toString() : requestId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(streamChat.stream(command).map(ChatEventMapper::toServerSentEvent));
    }
}
