package com.shoplab.helpdesk.chat.support;

import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptDefaults;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * A catalog for tests that need a given text for some prompts (as a version 1 of Langfuse) and the default text for
 * the others. A fixed version is never available here.
 */
public final class FixedPromptCatalog implements PromptCatalogPort {

    private final PromptDefaults defaults = new PromptDefaults(new PromptValidator(20_000));
    private final Map<PromptName, String> texts;

    private FixedPromptCatalog(Map<PromptName, String> texts) {
        this.texts = Map.copyOf(texts);
    }

    public static FixedPromptCatalog with(Map<PromptName, String> texts) {
        return new FixedPromptCatalog(texts);
    }

    @Override
    public ResolvedPrompt current(PromptName name) {
        String text = texts.get(name);
        return text == null ? defaults.get(name)
                : new ResolvedPrompt(text, PromptReference.langfuse(name, 1));
    }

    @Override
    public Mono<ResolvedPrompt> pinned(PromptName name, int version) {
        return Mono.error(PromptOverrideException.unavailable());
    }
}
