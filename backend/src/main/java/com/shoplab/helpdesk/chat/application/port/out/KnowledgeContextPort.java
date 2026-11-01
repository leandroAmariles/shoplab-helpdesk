package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.List;
import reactor.core.publisher.Mono;

public interface KnowledgeContextPort {

    /**
     * Candidate fragments for the question, closest first; empty when nothing qualifies. Any failure is a
     * {@code KnowledgeRetrievalException}.
     */
    Mono<List<ContextFragment>> retrieve(String question, int limit);
}
