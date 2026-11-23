package com.shoplab.helpdesk.eval.application.port.in;

import com.shoplab.helpdesk.eval.domain.model.RunListing;
import reactor.core.publisher.Mono;

/** The runs of a kind with their whole-set metrics, for the chart of the admin-eval screen (specs/LAB-32). */
public interface ListEvalRunsServicePort {

    Mono<RunListing> list(ListEvalRunsCommand command);
}
