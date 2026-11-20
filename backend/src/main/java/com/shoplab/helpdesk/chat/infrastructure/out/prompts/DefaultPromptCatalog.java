package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import reactor.core.publisher.Mono;

/**
 * The catalog when the prompts are not managed: always the default text, without any client, thread or connection. A
 * version cannot be fixed here, because there are no versions.
 */
public final class DefaultPromptCatalog implements PromptCatalogPort {

    private final PromptDefaults defaults;

    public DefaultPromptCatalog(PromptDefaults defaults) {
        this.defaults = defaults;
    }

    @Override
    public ResolvedPrompt current(PromptName name) {
        return defaults.get(name);
    }

    @Override
    public Mono<ResolvedPrompt> pinned(PromptName name, int version) {
        return Mono.error(PromptOverrideException.unavailable());
    }
}
