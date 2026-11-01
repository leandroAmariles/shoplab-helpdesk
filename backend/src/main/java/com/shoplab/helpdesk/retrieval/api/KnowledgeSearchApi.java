package com.shoplab.helpdesk.retrieval.api;

import java.util.List;
import reactor.core.publisher.Mono;

/** The search that {@code retrieval} publishes to other modules; they never reach its tables or internals. */
public interface KnowledgeSearchApi {

    /**
     * The chunks closest to the query among those indexed with the configured embedding model, closest first. It is
     * an empty list when none qualifies. Failures are the typed errors of the search (embedding service, database).
     */
    Mono<List<KnowledgeChunk>> search(String query, int limit);
}
