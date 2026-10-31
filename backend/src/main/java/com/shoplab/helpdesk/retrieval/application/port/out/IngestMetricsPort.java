package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import java.time.Duration;

public interface IngestMetricsPort {

    void recordParse(IngestOutcome outcome, Duration elapsed);
}
