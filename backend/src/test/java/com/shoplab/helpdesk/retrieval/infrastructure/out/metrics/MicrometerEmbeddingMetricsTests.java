package com.shoplab.helpdesk.retrieval.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerEmbeddingMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerEmbeddingMetrics metrics = new MicrometerEmbeddingMetrics(registry);

    @Test
    void recordsEachBatchByOutcome() {
        metrics.recordBatch(IndexOutcome.INDEXED, Duration.ofMillis(20));
        metrics.recordBatch(IndexOutcome.INDEXED, Duration.ofMillis(40));
        metrics.recordBatch(IndexOutcome.FAILED, Duration.ofMillis(5));

        assertThat(registry.get("retrieval.embedding.batch").tag("outcome", "indexed").timer().count()).isEqualTo(2);
        assertThat(registry.get("retrieval.embedding.batch").tag("outcome", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    void countsDocumentsByOutcome() {
        metrics.recordDocument(IndexOutcome.INDEXED);
        metrics.recordDocument(IndexOutcome.INDEXED);
        metrics.recordDocument(IndexOutcome.SKIPPED);

        assertThat(registry.get("retrieval.embedding.documents").tag("outcome", "indexed").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("retrieval.embedding.documents").tag("outcome", "skipped").counter().count())
                .isEqualTo(1);
    }

    @Test
    void recordsTheDurationOfEachSearch() {
        metrics.recordSearch(Duration.ofMillis(30));

        assertThat(registry.get("retrieval.search").timer().count()).isEqualTo(1);
    }
}
