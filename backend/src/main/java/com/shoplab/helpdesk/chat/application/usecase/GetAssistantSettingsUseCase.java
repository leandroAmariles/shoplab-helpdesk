package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.AssistantSettingsApi;
import com.shoplab.helpdesk.chat.application.port.in.GetAssistantSettingsServicePort;
import reactor.core.publisher.Mono;

/** Reads active assistant settings reactively from in-memory state without blocking I/O. */
public class GetAssistantSettingsUseCase implements GetAssistantSettingsServicePort {

    private final AssistantSettingsApi settingsApi;

    public GetAssistantSettingsUseCase(AssistantSettingsApi settingsApi) {
        this.settingsApi = settingsApi;
    }

    @Override
    public Mono<AssistantSettings> current() {
        return Mono.fromSupplier(settingsApi::current);
    }
}
