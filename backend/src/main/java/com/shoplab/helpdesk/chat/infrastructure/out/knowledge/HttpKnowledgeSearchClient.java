package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import com.shoplab.contracts.retrieval.client.KnowledgeSearchApi;
import com.shoplab.contracts.retrieval.model.KnowledgeSearchHitResponse;
import com.shoplab.contracts.retrieval.model.KnowledgeSearchResponse;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.config.RetrievalClientProperties;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Gets the context from {@code retrieval-service} over HTTP (LAB-129). One attempt with a bounded wait; any failure is
 * the retrieval error of the chat. The threshold, the number of fragments and the context budget stay in the chat.
 */
@Component
public class HttpKnowledgeSearchClient implements KnowledgeContextPort {

    private static final Logger LOG = LoggerFactory.getLogger(HttpKnowledgeSearchClient.class);

    private final KnowledgeSearchApi search;
    private final Duration timeout;

    @Autowired
    public HttpKnowledgeSearchClient(KnowledgeSearchApi retrievalSearchApi, RetrievalClientProperties properties) {
        this(retrievalSearchApi, properties.timeout());
    }

    public HttpKnowledgeSearchClient(KnowledgeSearchApi search, Duration timeout) {
        this.search = search;
        this.timeout = timeout;
    }

    @Override
    public Mono<List<ContextFragment>> retrieve(String question, int limit) {
        return Mono.defer(() -> search.searchKnowledge(question, limit))
                .timeout(timeout)
                .flatMap(response -> Mono.justOrEmpty(response.getBody()))
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("The search answered without a body")))
                .map(this::toFragments)
                .onErrorMap(KnowledgeRetrievalException::new);
    }

    /** A hit without text carries nothing for the model; it is dropped, and the log never says what it held. */
    private List<ContextFragment> toFragments(KnowledgeSearchResponse response) {
        return response.getResults().stream()
                .filter(hit -> {
                    boolean usable = StringUtils.hasText(hit.getText());
                    if (!usable) {
                        LOG.warn("Dropped blank chunk from HTTP retrieval: source={}, position={}",
                                hit.getSource(), hit.getPosition());
                    }
                    return usable;
                })
                .map(HttpKnowledgeSearchClient::toFragment)
                .toList();
    }

    private static ContextFragment toFragment(KnowledgeSearchHitResponse hit) {
        return new ContextFragment(hit.getSource(), titleOf(hit), Objects.toString(hit.getSection(), ""),
                hit.getPosition(), hit.getText(), hit.getScore());
    }

    /** The customer reads the title, not the file name: a missing one is derived from the name as a last resort. */
    private static String titleOf(KnowledgeSearchHitResponse hit) {
        if (StringUtils.hasText(hit.getTitle())) {
            return hit.getTitle().strip();
        }
        String name = hit.getSource();
        int extension = name.lastIndexOf('.');
        String words = (extension > 0 ? name.substring(0, extension) : name).replaceAll("[-_]+", " ").strip();
        return words.isEmpty() ? name : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
