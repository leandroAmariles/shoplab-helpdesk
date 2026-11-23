package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsCommand;
import com.shoplab.helpdesk.eval.application.port.in.ListEvalRunsServicePort;
import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reads the runs of a kind. Reading needs no token and writes nothing: it works while saving is off (the list is then
 * empty), and the request is checked before the database is touched.
 */
@Service
public class ListEvalRunsUseCase implements ListEvalRunsServicePort {

    private final EvalHistoryQueryValidator validator;
    private final EvalHistoryPort history;

    public ListEvalRunsUseCase(EvalHistoryQueryValidator validator, EvalHistoryPort history) {
        this.validator = validator;
        this.history = history;
    }

    @Override
    public Mono<RunListing> list(ListEvalRunsCommand command) {
        return Mono.defer(() -> {
            EvalHistoryQueryValidator.ListQuery query = validator.list(command);
            return history.list(query.kind(), query.origin(), query.limit());
        });
    }
}
