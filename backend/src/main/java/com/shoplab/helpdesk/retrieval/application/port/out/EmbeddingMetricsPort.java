package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexOutcome;
import java.time.Duration;

public interface EmbeddingMetricsPort {

    void recordBatch(IndexOutcome outcome, Duration elapsed);

    void recordDocument(IndexOutcome outcome);

    void recordSearch(Duration elapsed);
}
