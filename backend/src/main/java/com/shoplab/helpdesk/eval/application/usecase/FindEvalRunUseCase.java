package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.FindEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.model.StoredRunSummary;
import com.shoplab.helpdesk.eval.domain.model.constants.EvalConstants;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Whether a run is already saved. The tool asks it before it measures: the answer also proves that the token is right
 * and that the database and the schema work.
 */
@Service
public class FindEvalRunUseCase implements FindEvalRunServicePort {

    private final EvalAccessGuard guard;
    private final EvalRunStorePort store;

    public FindEvalRunUseCase(EvalAccessGuard guard, EvalRunStorePort store) {
        this.guard = guard;
        this.store = store;
    }

    @Override
    public Mono<StoredRunSummary> find(String token, String runId) {
        return Mono.defer(() -> {
            guard.check(token);
            if (runId == null || !EvalConstants.RUN_ID.matcher(runId).matches()) {
                throw new EvalRunInvalidException("run_id: must match " + EvalConstants.RUN_ID.pattern());
            }
            return store.find(runId).flatMap(Mono::justOrEmpty)
                    .switchIfEmpty(Mono.error(EvalRunNotFoundException::new));
        });
    }
}
