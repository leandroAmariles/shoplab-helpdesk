package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.List;

/** One execution of the retrieval or the quality measurement, with everything that makes it comparable. */
public record EvalRun(String runId, RunKind kind, Instant startedAt, Instant finishedAt, String origin,
        String commitSha, String datasetHash, Integer casesMeasured, Integer casesExcluded, String assistantModel,
        String embeddingModel, List<PromptVersion> prompts, JudgeInfo judge, EvaluatorInfo evaluator,
        RetrievalConfig retrievalConfig, List<EvalResult> results) {

    public EvalRun withResults(List<EvalResult> newResults) {
        return new EvalRun(runId, kind, startedAt, finishedAt, origin, commitSha, datasetHash, casesMeasured,
                casesExcluded, assistantModel, embeddingModel, prompts, judge, evaluator, retrievalConfig, newResults);
    }
}
