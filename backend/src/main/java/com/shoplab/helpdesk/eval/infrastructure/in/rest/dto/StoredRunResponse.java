package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** A run that is already saved (contracts/eval-runs-api.md). */
public record StoredRunResponse(@JsonProperty("run_id") String runId, String kind,
        @JsonProperty("started_at") Instant startedAt, @JsonProperty("recorded_at") Instant recordedAt) {
}
