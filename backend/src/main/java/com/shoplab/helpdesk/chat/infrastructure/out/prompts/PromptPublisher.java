package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient.FetchOutcome;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Seeds a Langfuse that has no prompts (LAB-85, FR-005): creates, with the default text and the label the chat reads,
 * only the prompts that do not exist. It never creates a version of a prompt that exists, whatever its text, and never
 * moves a label: what is in Langfuse is the source of truth. Running it again changes nothing.
 *
 * <p>The API answers 404 both for a prompt that does not exist and for a label that does not exist on one that does, so
 * it asks for the {@code latest} label, which Langfuse puts on the newest version of every prompt.
 */
public final class PromptPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(PromptPublisher.class);

    private final LangfusePromptClient client;
    private final PromptDefaults defaults;
    private final String label;

    public PromptPublisher(LangfusePromptClient client, PromptDefaults defaults, String label) {
        this.client = client;
        this.defaults = defaults;
        this.label = label;
    }

    /**
     * @param candidates the prompts that were not found with the label; each is checked before it is created
     * @return the prompts it created
     */
    public Mono<List<PromptName>> publishMissing(Collection<PromptName> candidates) {
        return Flux.fromIterable(candidates)
                .concatMap(this::publishIfAbsent)
                .collectList();
    }

    private Mono<PromptName> publishIfAbsent(PromptName name) {
        return client.fetchByLabel(name.langfuseName(), LangfusePromptClient.LATEST_LABEL)
                .flatMap(result -> {
                    if (result.outcome() != FetchOutcome.MISSING) {
                        return Mono.empty();
                    }
                    return client.create(name.langfuseName(), defaults.get(name).text(), label)
                            .flatMap(created -> {
                                if (created) {
                                    LOG.info("Prompt published with its default text: prompt={}",
                                            name.langfuseName());
                                    return Mono.just(name);
                                }
                                LOG.warn("Prompt could not be published, it is tried again at the next start: "
                                        + "prompt={}", name.langfuseName());
                                return Mono.empty();
                            });
                });
    }
}
