package com.shoplab.helpdesk.retrieval.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.KnowledgeSearchHitResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.KnowledgeSearchResponse;
import java.util.List;

public final class KnowledgeSearchResponseMapper {

    private KnowledgeSearchResponseMapper() {
    }

    /** Keeps the order of the results and drops the identifiers that only matter inside the module. */
    public static KnowledgeSearchResponse toResponse(String model, List<SearchResult> results) {
        return new KnowledgeSearchResponse(model, results.stream().map(KnowledgeSearchResponseMapper::toHit).toList());
    }

    private static KnowledgeSearchHitResponse toHit(SearchResult result) {
        return new KnowledgeSearchHitResponse(result.source(), result.title(), result.section(), result.position(),
                result.score(), result.text());
    }
}
