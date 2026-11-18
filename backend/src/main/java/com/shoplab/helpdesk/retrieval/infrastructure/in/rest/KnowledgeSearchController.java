package com.shoplab.helpdesk.retrieval.infrastructure.in.rest;

import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.dto.KnowledgeSearchResponse;
import com.shoplab.helpdesk.retrieval.infrastructure.in.rest.util.mapper.KnowledgeSearchResponseMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Read-only access to the same search the chat uses (specs/LAB-28-retrieval-metrics, ADR-0024). It lets the
 * evaluation in {@code eval/} measure the real search without reaching the tables of this module (ADR-0015). It
 * returns the order of the search as is: the similarity threshold and the context budget belong to the chat
 * (ADR-0018). Errors are typed and mapped only by the {@code GlobalExceptionHandler}; the query is validated by the
 * use case, so a missing or blank one ends as the same typed error.
 */
@RestController
@RequestMapping("/api/knowledge/search")
public class KnowledgeSearchController {

    private final SearchChunksServicePort search;

    public KnowledgeSearchController(SearchChunksServicePort search) {
        this.search = search;
    }

    @GetMapping
    public Mono<KnowledgeSearchResponse> search(@RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return search.search(query, limit)
                .map(results -> KnowledgeSearchResponseMapper.toResponse(search.embeddingModel(), results));
    }
}
