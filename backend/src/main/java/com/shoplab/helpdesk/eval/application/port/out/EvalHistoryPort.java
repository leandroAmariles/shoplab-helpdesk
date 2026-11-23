package com.shoplab.helpdesk.eval.application.port.out;

import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * Reads the history of evaluation runs (specs/LAB-32). It only reads: saving a run goes through
 * {@link EvalRunStorePort}, which is protected by a token. Only whole runs are ever visible.
 */
public interface EvalHistoryPort {

    /**
     * The most recent runs of a kind that match the origin, oldest first, with their whole-set metrics.
     *
     * @param origin the origin to keep, or {@code null} for all
     */
    Mono<RunListing> list(RunKind kind, String origin, int limit);

    Mono<Optional<StoredRun>> find(String runId);
}
