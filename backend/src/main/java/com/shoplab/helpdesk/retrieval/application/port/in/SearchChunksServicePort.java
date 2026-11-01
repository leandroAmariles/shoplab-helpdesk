package com.shoplab.helpdesk.retrieval.application.port.in;

import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import java.util.List;
import reactor.core.publisher.Mono;

public interface SearchChunksServicePort {

    /**
     * Finds the chunks closest to the query among those that have a vector from the configured model. A blank query
     * is rejected without calling the model; with nothing to compare the result is an empty list. A null
     * {@code limit} uses the configured one.
     */
    Mono<List<SearchResult>> search(String query, Integer limit);
}
