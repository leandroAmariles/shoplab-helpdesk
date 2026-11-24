package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import com.shoplab.helpdesk.chat.application.port.in.GetAssistantSettingsServicePort;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.AssistantSettingsResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper.AssistantSettingsRestMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Exposes active assistant retrieval and prompt settings over HTTP for eval consumers (LAB-106). */
@RestController
@RequestMapping("/api/assistant/settings")
public class AssistantSettingsController {

    private final GetAssistantSettingsServicePort settingsPort;

    public AssistantSettingsController(GetAssistantSettingsServicePort settingsPort) {
        this.settingsPort = settingsPort;
    }

    @GetMapping
    public Mono<AssistantSettingsResponse> get() {
        return settingsPort.current().map(AssistantSettingsRestMapper::toResponse);
    }
}
