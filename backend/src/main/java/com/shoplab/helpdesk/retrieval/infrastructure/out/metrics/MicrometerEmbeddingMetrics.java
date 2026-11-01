package com.shoplab.helpdesk.retrieval.infrastructure.out.metrics;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Embeddings and search metrics on the Prometheus registry; the only tag has a fixed set of values. */
@Component
public class MicrometerEmbeddingMetrics implements EmbeddingMetricsPort {

    private final MeterRegistry registry;

    public MicrometerEmbeddingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordBatch(IndexOutcome outcome, Duration elapsed) {
        Timer.builder(RetrievalConstants.EMBEDDING_BATCH_METRIC)
                .description("Duration of a batch sent to the embedding model by outcome")
                .tag(RetrievalConstants.OUTCOME_TAG, outcome.tagValue())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }

    @Override
    public void recordDocument(IndexOutcome outcome) {
        Counter.builder(RetrievalConstants.EMBEDDING_DOCUMENTS_METRIC)
                .description("Documents processed by the embeddings step by outcome")
                .tag(RetrievalConstants.OUTCOME_TAG, outcome.tagValue())
                .register(registry)
                .increment();
    }

    @Override
    public void recordSearch(Duration elapsed) {
        Timer.builder(RetrievalConstants.SEARCH_METRIC)
                .description("Duration of a vector search, including the embedding of the query")
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }
}
