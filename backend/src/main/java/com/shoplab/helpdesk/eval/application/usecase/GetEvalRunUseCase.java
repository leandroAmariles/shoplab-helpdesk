package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.GetEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Reads one saved run. It needs no token and it only reads; the run id is checked before the database is touched. */
@Service
public class GetEvalRunUseCase implements GetEvalRunServicePort {

    private final EvalHistoryQueryValidator validator;
    private final EvalHistoryPort history;

    public GetEvalRunUseCase(EvalHistoryQueryValidator validator, EvalHistoryPort history) {
        this.validator = validator;
        this.history = history;
    }

    @Override
    public Mono<StoredRun> get(String runId) {
        return Mono.defer(() -> history.find(validator.runId(runId)))
                .flatMap(Mono::justOrEmpty)
                .switchIfEmpty(Mono.error(EvalRunNotFoundException::new));
    }
}
