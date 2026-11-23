package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;

/** The few fields that tell the two runs of a comparison apart. */
public record RunHeader(String runId, RunKind kind, Instant startedAt, String origin) {
}
