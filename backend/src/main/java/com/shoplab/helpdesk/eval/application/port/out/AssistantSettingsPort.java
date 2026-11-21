package com.shoplab.helpdesk.eval.application.port.out;

import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import reactor.core.publisher.Mono;

/** Where the module learns what the assistant uses now. */
public interface AssistantSettingsPort {

    Mono<AssistantSettingsSnapshot> current();
}
