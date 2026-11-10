package com.shoplab.helpdesk.retrieval.infrastructure.out.metrics;

import com.shoplab.helpdesk.retrieval.application.port.out.UploadMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Upload metrics on the Prometheus registry. The documents gauge holds the counts of the last list that was read: it
 * is not refreshed by querying the database from the scrape, which runs on the event loop.
 */
@Component
public class MicrometerUploadMetrics implements UploadMetricsPort {

    private final MeterRegistry registry;
    private final Map<IndexStatus, AtomicLong> documents = new ConcurrentHashMap<>();

    public MicrometerUploadMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordUpload(IngestOutcome outcome, Duration elapsed) {
        Timer.builder(RetrievalConstants.UPLOAD_METRIC)
                .description("Duration of a document upload by outcome")
                .tag(RetrievalConstants.OUTCOME_TAG, outcome.tagValue())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }

    @Override
    public void recordDocumentsByStatus(Map<IndexStatus, Long> counts) {
        for (IndexStatus status : IndexStatus.values()) {
            gauge(status).set(counts.getOrDefault(status, 0L));
        }
    }

    private AtomicLong gauge(IndexStatus status) {
        return documents.computeIfAbsent(status, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(RetrievalConstants.DOCUMENTS_METRIC, value, AtomicLong::get)
                    .description("Documents of the knowledge base by indexing state")
                    .tag(RetrievalConstants.STATUS_TAG, key.getExternalValue())
                    .strongReference(true)
                    .register(registry);
            return value;
        });
    }
}
