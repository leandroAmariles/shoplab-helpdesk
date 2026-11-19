package com.shoplab.helpdesk.retrieval.application.port.out;

import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import java.time.Duration;
import java.util.Map;

/** Usage metrics of the document screen; tags take a fixed set of values, never names or text of documents. */
public interface UploadMetricsPort {

    void recordUpload(IngestOutcome outcome, Duration elapsed);

    /** Publishes how many documents are in each state; a state missing from the map has none. */
    void recordDocumentsByStatus(Map<IndexStatus, Long> counts);
}
