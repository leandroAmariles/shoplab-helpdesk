package com.shoplab.helpdesk.chat.application.port.in;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import reactor.core.publisher.Mono;

/** Inbound port exposing active in-memory assistant settings for HTTP consumers. */
public interface GetAssistantSettingsServicePort {

    Mono<AssistantSettings> current();
}
