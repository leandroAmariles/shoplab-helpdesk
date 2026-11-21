package com.shoplab.helpdesk.eval.application.port.in;

import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import reactor.core.publisher.Mono;

/** The settings the assistant uses now, for the tool to record and to detect a change during a measurement. */
public interface ReadAssistantSettingsServicePort {

    Mono<AssistantSettingsSnapshot> read(String token);
}
