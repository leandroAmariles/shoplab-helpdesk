package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.application.port.in.SaveEvalRunServicePort;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.model.EvalResult;
import com.shoplab.helpdesk.eval.domain.model.EvalRun;
import com.shoplab.helpdesk.eval.domain.model.SavedRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Saves a run: the token first, then the rules of the data model, then the revision of every case, then the whole run
 * in one go. Only the run id, the kind and the counts are logged, never a question or an answer.
 */
@Service
public class SaveEvalRunUseCase implements SaveEvalRunServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(SaveEvalRunUseCase.class);

    private final EvalAccessGuard guard;
    private final EvalRunValidator validator;
    private final CaseRevisionHasher hasher;
    private final EvalRunStorePort store;

    public SaveEvalRunUseCase(EvalAccessGuard guard, EvalRunValidator validator, CaseRevisionHasher hasher,
            EvalRunStorePort store) {
        this.guard = guard;
        this.validator = validator;
        this.hasher = hasher;
        this.store = store;
    }

    @Override
    public Mono<SavedRun> save(String token, EvalRun run) {
        return Mono.defer(() -> {
            guard.check(token);
            validator.validate(run);
            return store.save(withRevisions(run));
        }).doOnNext(saved -> LOG.info("Evaluation run saved: runId={}, kind={}, results={}, newCaseRevisions={}",
                saved.runId(), saved.kind(), saved.results(), saved.newCaseRevisions()));
    }

    private EvalRun withRevisions(EvalRun run) {
        return run.withResults(run.results().stream()
                .map(result -> new EvalResult(result.evalCase().withContentHash(hasher.hash(result.evalCase())),
                        result.caseKind(), result.scores(), result.retrievedDocuments(), result.abstained(),
                        result.traceId()))
                .toList());
    }
}
