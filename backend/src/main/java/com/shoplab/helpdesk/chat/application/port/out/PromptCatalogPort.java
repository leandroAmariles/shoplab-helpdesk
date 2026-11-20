package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import reactor.core.publisher.Mono;

/**
 * Where the chat gets its prompts (LAB-85). The text is managed outside the code, in Langfuse; this port hides whether
 * it comes from there or from the default text of the repository.
 */
public interface PromptCatalogPort {

    /**
     * The prompt the chat serves now: the last valid version loaded from Langfuse, or the default text while there is
     * none. It only reads memory (no network, no waiting) and never fails, so a turn cannot be delayed or broken by
     * the state of Langfuse.
     */
    ResolvedPrompt current(PromptName name);

    /**
     * A given version of a prompt, for an evaluation that fixes it. Unlike {@link #current}, it fails (with
     * {@code PromptOverrideException}) when the version does not exist, is not valid or cannot be read: it never falls
     * back to another text.
     */
    Mono<ResolvedPrompt> pinned(PromptName name, int version);
}
