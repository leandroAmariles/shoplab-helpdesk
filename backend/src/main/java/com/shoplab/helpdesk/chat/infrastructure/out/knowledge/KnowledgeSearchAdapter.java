package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import com.shoplab.helpdesk.retrieval.api.KnowledgeSearchApi;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Gets the context from the search that {@code retrieval} publishes; the rest of {@code chat} never sees it. */
@Component
public class KnowledgeSearchAdapter implements KnowledgeContextPort {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeSearchAdapter.class);

    private final KnowledgeSearchApi search;
    private final ContextFragmentMapper mapper;

    public KnowledgeSearchAdapter(KnowledgeSearchApi search, ContextFragmentMapper mapper) {
        this.search = search;
        this.mapper = mapper;
    }

    @Override
    public Mono<List<ContextFragment>> retrieve(String question, int limit) {
        return Mono.defer(() -> search.search(question, limit))
                .map(this::toFragments)
                .onErrorMap(KnowledgeRetrievalException::new);
    }

    /** A chunk without text carries nothing for the model; it is dropped, and the log never says what it held. */
    private List<ContextFragment> toFragments(List<KnowledgeChunk> chunks) {
        return chunks.stream()
                .filter(chunk -> {
                    boolean usable = chunk.text() != null && !chunk.text().isBlank();
                    if (!usable) {
                        LOG.warn("A chunk without text was dropped from the context: source={}, position={}",
                                chunk.source(), chunk.position());
                    }
                    return usable;
                })
                .map(mapper::toFragment)
                .toList();
    }
}
