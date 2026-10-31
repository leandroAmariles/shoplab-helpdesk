package com.shoplab.helpdesk.retrieval.infrastructure.out.metrics;

import com.shoplab.helpdesk.retrieval.application.port.out.IngestMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Ingestion metrics on the Prometheus registry; the only tag has a fixed set of values. */
@Component
public class MicrometerIngestMetrics implements IngestMetricsPort {

    private final MeterRegistry registry;

    public MicrometerIngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordParse(IngestOutcome outcome, Duration elapsed) {
        Timer.builder(RetrievalConstants.PARSE_METRIC)
                .description("Duration of the call to the ingest service by outcome")
                .tag(RetrievalConstants.OUTCOME_TAG, outcome.tagValue())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }
}
