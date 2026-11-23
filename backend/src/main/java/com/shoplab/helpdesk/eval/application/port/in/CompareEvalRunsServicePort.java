package com.shoplab.helpdesk.eval.application.port.in;

import com.shoplab.helpdesk.eval.domain.model.RunComparison;
import reactor.core.publisher.Mono;

/** Two saved runs of the same kind compared for one metric, for the admin-eval screen (specs/LAB-32). */
public interface CompareEvalRunsServicePort {

    Mono<RunComparison> compare(CompareEvalRunsCommand command);
}
