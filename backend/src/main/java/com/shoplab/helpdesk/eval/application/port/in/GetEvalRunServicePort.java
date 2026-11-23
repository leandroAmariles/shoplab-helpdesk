package com.shoplab.helpdesk.eval.application.port.in;

import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import reactor.core.publisher.Mono;

/** One saved run whole, for the detail of the admin-eval screen (specs/LAB-32). */
public interface GetEvalRunServicePort {

    Mono<StoredRun> get(String runId);
}
