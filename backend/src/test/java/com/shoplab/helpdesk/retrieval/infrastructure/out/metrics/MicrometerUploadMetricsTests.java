package com.shoplab.helpdesk.retrieval.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MicrometerUploadMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerUploadMetrics metrics = new MicrometerUploadMetrics(registry);

    @Test
    void recordsEachUploadByOutcome() {
        metrics.recordUpload(IngestOutcome.OK, Duration.ofMillis(120));
        metrics.recordUpload(IngestOutcome.OK, Duration.ofMillis(80));
        metrics.recordUpload(IngestOutcome.REJECTED, Duration.ofMillis(5));
        metrics.recordUpload(IngestOutcome.FAILED, Duration.ofMillis(30));

        assertThat(registry.get("retrieval.upload").tag("outcome", "ok").timer().count()).isEqualTo(2);
        assertThat(registry.get("retrieval.upload").tag("outcome", "rejected").timer().count()).isEqualTo(1);
        assertThat(registry.get("retrieval.upload").tag("outcome", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    void publishesTheDocumentsOfEachStateAndZeroForTheMissingOnes() {
        metrics.recordDocumentsByStatus(Map.of(IndexStatus.PENDING, 2L, IndexStatus.INDEXED, 5L));

        assertThat(registry.get("retrieval.documents").tag("status", "PENDING").gauge().value()).isEqualTo(2);
        assertThat(registry.get("retrieval.documents").tag("status", "INDEXED").gauge().value()).isEqualTo(5);
        assertThat(registry.get("retrieval.documents").tag("status", "ERROR").gauge().value()).isZero();
    }

    @Test
    void theLatestCountsReplaceThePreviousOnes() {
        metrics.recordDocumentsByStatus(Map.of(IndexStatus.PENDING, 2L));
        metrics.recordDocumentsByStatus(Map.of(IndexStatus.INDEXED, 2L));

        assertThat(registry.get("retrieval.documents").tag("status", "PENDING").gauge().value()).isZero();
        assertThat(registry.get("retrieval.documents").tag("status", "INDEXED").gauge().value()).isEqualTo(2);
        assertThat(registry.get("retrieval.documents").gauges()).hasSize(3);
    }

    @Test
    void noTagCarriesTheNameOfADocument() {
        metrics.recordUpload(IngestOutcome.OK, Duration.ofMillis(1));
        metrics.recordDocumentsByStatus(Map.of(IndexStatus.ERROR, 1L));

        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "status")));
        assertThat(registry.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).distinct())
                .containsExactlyInAnyOrder("retrieval.upload", "retrieval.documents");
    }
}
