package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.List;

/** A saved run whole: how it was measured, its summary and the result of every case. */
public record StoredRun(String runId, RunKind kind, Instant startedAt, Instant finishedAt, Instant recordedAt,
        String origin, String commitSha, String datasetHash, int casesMeasured, int casesExcluded,
        String assistantModel, String embeddingModel, List<PromptVersion> prompts, JudgeInfo judge,
        EvaluatorInfo evaluator, RetrievalConfig retrievalConfig, List<ScopeSummary> summary,
        List<StoredResult> results) {
}
