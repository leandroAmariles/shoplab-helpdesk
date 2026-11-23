package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.CompareEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.CompareEvalRunsServicePort;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.RunComparator;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunInvalidException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunNotFoundException;
import com.shoplab.helpdesk.eval.domain.exception.EvalRunsNotComparableException;
import com.shoplab.helpdesk.eval.domain.model.RunComparison;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Compares two saved runs. It needs no token and it only reads: the request is checked before any run is read, the
 * runs must exist and be of the same kind, and the metric must be one that at least one of them has.
 */
@Service
public class CompareEvalRunsUseCase implements CompareEvalRunsServicePort {

    private final EvalHistoryQueryValidator validator;
    private final EvalHistoryPort history;

    public CompareEvalRunsUseCase(EvalHistoryQueryValidator validator, EvalHistoryPort history) {
        this.validator = validator;
        this.history = history;
    }

    @Override
    public Mono<RunComparison> compare(CompareEvalRunsCommand command) {
        return Mono.defer(() -> {
            EvalHistoryQueryValidator.CompareQuery query = validator.compare(command);
            return Mono.zip(find(query.base()), find(query.target()))
                    .map(runs -> compare(runs.getT1(), runs.getT2(), query));
        });
    }

    private Mono<StoredRun> find(String runId) {
        return history.find(runId).flatMap(Mono::justOrEmpty).switchIfEmpty(Mono.error(EvalRunNotFoundException::new));
    }

    private static RunComparison compare(StoredRun base, StoredRun target,
            EvalHistoryQueryValidator.CompareQuery query) {
        if (base.kind() != target.kind()) {
            throw new EvalRunsNotComparableException();
        }
        if (!RunComparator.metricNames(base, target).contains(query.metric())) {
            throw new EvalRunInvalidException("metric: neither run has this metric");
        }
        return RunComparator.compare(base, target, query.metric(), query.drop());
    }
}
