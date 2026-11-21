package com.shoplab.helpdesk.eval.application.port.in;

import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import reactor.core.publisher.Mono;

/** Tells whether a run is already saved, so the tool can check before it measures. */
public interface FindEvalRunServicePort {

    Mono<StoredRunSummary> find(String token, String runId);
}
