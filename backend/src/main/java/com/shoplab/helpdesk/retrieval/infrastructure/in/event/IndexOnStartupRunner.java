package com.shoplab.helpdesk.retrieval.infrastructure.in.event;

import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.ReindexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.config.EmbeddingProperties;
import com.shoplab.helpdesk.retrieval.domain.model.IndexingSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.ReindexMode;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * At start-up, indexes the documents an interrupted run left pending (never the failed ones) and, when
 * {@code reindex-on-startup} says so, reindexes those with vectors from another model: this is the way to change the
 * model by configuration alone. It runs in the background and a failure never stops the start-up.
 */
@Component
@ConditionalOnProperty(name = "shoplab.retrieval.embedding.auto-index", havingValue = "true", matchIfMissing = true)
public class IndexOnStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(IndexOnStartupRunner.class);

    private final IndexDocumentsServicePort indexDocuments;
    private final ReindexDocumentsServicePort reindexDocuments;
    private final EmbeddingProperties properties;

    public IndexOnStartupRunner(IndexDocumentsServicePort indexDocuments,
            ReindexDocumentsServicePort reindexDocuments, EmbeddingProperties properties) {
        this.indexDocuments = indexDocuments;
        this.reindexDocuments = reindexDocuments;
        this.properties = properties;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        ReindexMode mode = properties.reindexMode();
        run(mode).subscribe(
                summary -> LOG.info("Start-up indexing finished: mode={}, documents={}, chunks={}, failed={}",
                        mode, summary.documents(), summary.chunks(), summary.failed().size()),
                error -> LOG.warn("Start-up indexing failed: mode={}, error={}", mode, error.toString()));
    }

    Mono<IndexingSummary> run(ReindexMode mode) {
        return switch (mode) {
            case NONE -> indexDocuments.indexPending(false);
            case CHANGED_MODEL -> reindexDocuments.reindex(false);
            case ALL -> reindexDocuments.reindex(true);
        };
    }
}
