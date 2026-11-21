package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Body of the API of the evaluation history (contracts/eval-runs-api.md); a key that is not defined is refused. */
public record EvalRunRequest(@JsonProperty("run_id") String runId, String kind,
        @JsonProperty("started_at") Instant startedAt, @JsonProperty("finished_at") Instant finishedAt, String origin,
        @JsonProperty("commit_sha") String commitSha, @JsonProperty("dataset_hash") String datasetHash,
        @JsonProperty("cases_measured") Integer casesMeasured,
        @JsonProperty("cases_excluded") Integer casesExcluded,
        @JsonProperty("assistant_model") String assistantModel,
        @JsonProperty("embedding_model") String embeddingModel, List<PromptVersionRequest> prompts,
        JudgeRequest judge, EvaluatorRequest evaluator,
        @JsonProperty("retrieval_config") RetrievalConfigRequest retrievalConfig,
        List<ResultRequest> results) {

    @JsonAnySetter
    void unknown(String key, Object value) {
        UnknownProperty.reject(key);
    }
}
