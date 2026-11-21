package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.ReadAssistantSettingsServicePort;
import com.shoplab.helpdesk.eval.application.port.out.AssistantSettingsPort;
import com.shoplab.helpdesk.eval.domain.model.AssistantSettingsSnapshot;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** What the assistant uses now, for whoever holds the token. */
@Service
public class ReadAssistantSettingsUseCase implements ReadAssistantSettingsServicePort {

    private final EvalAccessGuard guard;
    private final AssistantSettingsPort settings;

    public ReadAssistantSettingsUseCase(EvalAccessGuard guard, AssistantSettingsPort settings) {
        this.guard = guard;
        this.settings = settings;
    }

    @Override
    public Mono<AssistantSettingsSnapshot> read(String token) {
        return Mono.defer(() -> {
            guard.check(token);
            return settings.current();
        });
    }
}
