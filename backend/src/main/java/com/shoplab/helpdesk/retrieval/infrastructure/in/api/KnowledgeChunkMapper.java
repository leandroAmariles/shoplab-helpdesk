package com.shoplab.helpdesk.retrieval.infrastructure.in.api;

import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import org.springframework.stereotype.Component;

/** Publishes a search result without the identifiers that only matter inside {@code retrieval}. */
@Component
public class KnowledgeChunkMapper {

    public KnowledgeChunk toChunk(SearchResult result) {
        return new KnowledgeChunk(result.source(), result.title(), result.section(), result.position(),
                result.text(), result.score());
    }
}
