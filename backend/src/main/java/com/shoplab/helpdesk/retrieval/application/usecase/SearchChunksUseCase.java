package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingPort;
import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingStorePort;
import com.shoplab.helpdesk.retrieval.config.EmbeddingProperties;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Vector search: the query is embedded with the configured model and compared only with that model's vectors. */
@Service
public class SearchChunksUseCase implements SearchChunksServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(SearchChunksUseCase.class);

    private final EmbeddingPort embedding;
    private final EmbeddingStorePort store;
    private final EmbeddingMetricsPort metrics;
    private final EmbeddingProperties properties;

    public SearchChunksUseCase(EmbeddingPort embedding, EmbeddingStorePort store, EmbeddingMetricsPort metrics,
            EmbeddingProperties properties) {
        this.embedding = embedding;
        this.store = store;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public Mono<List<SearchResult>> search(String query, Integer limit) {
        return Mono.defer(() -> {
            if (query == null || query.isBlank()) {
                throw ExceptionUtil.business(ResponseCodeEnum.SEARCH_QUERY_INVALID);
            }
            int effectiveLimit = limit == null ? properties.searchLimit()
                    : Math.max(1, Math.min(limit, RetrievalConstants.MAX_SEARCH_LIMIT));
            long startedAt = System.nanoTime();
            String model = embedding.modelName();
            return embedding.embed(List.of(query))
                    .flatMap(vectors -> store.search(vectors.get(0), model, effectiveLimit))
                    .flatMap(results -> results.isEmpty() ? warnIfModelChanged(model).thenReturn(results)
                            : Mono.just(results))
                    .doFinally(signal -> metrics.recordSearch(Duration.ofNanos(System.nanoTime() - startedAt)));
        });
    }

    /** An empty answer after a model change that was not reindexed is explained in the log, not hidden. */
    private Mono<Void> warnIfModelChanged(String model) {
        return store.hasChunksFromOtherModels(model)
                .doOnNext(changed -> {
                    if (changed) {
                        LOG.warn("No chunk has a vector from model {}: there are vectors from other models, "
                                + "reindex to use the configured one", model);
                    }
                })
                .onErrorResume(error -> Mono.empty())
                .then();
    }
}
