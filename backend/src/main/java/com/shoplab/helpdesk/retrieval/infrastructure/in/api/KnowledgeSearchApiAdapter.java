package com.shoplab.helpdesk.retrieval.infrastructure.in.api;

import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import com.shoplab.helpdesk.retrieval.api.KnowledgeSearchApi;
import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Serves the module's published search with the vector search use case. */
@Component
public class KnowledgeSearchApiAdapter implements KnowledgeSearchApi {

    private final SearchChunksServicePort search;
    private final KnowledgeChunkMapper mapper;

    public KnowledgeSearchApiAdapter(SearchChunksServicePort search, KnowledgeChunkMapper mapper) {
        this.search = search;
        this.mapper = mapper;
    }

    @Override
    public Mono<List<KnowledgeChunk>> search(String query, int limit) {
        return search.search(query, limit).map(results -> results.stream().map(mapper::toChunk).toList());
    }
}
