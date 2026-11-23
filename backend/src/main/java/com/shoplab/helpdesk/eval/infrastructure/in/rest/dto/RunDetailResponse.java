package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One saved run whole (contracts/eval-history-api.md): how it was measured, its summary and the result of every
 * case. The fields are a closed list: none of them holds the text of an answer of the assistant or of a fragment.
 */
public record RunDetailResponse(RunPart run, List<ScopePart> summary, List<ResultPart> results) {

    /** How the run was measured. {@code judge} and {@code evaluator} only exist in a quality run. */
    public record RunPart(@JsonProperty("run_id") String runId, String kind,
            @JsonProperty("started_at") Instant startedAt, @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("recorded_at") Instant recordedAt, String origin,
            @JsonProperty("commit_sha") String commitSha, @JsonProperty("dataset_hash") String datasetHash,
            @JsonProperty("cases_measured") int casesMeasured, @JsonProperty("cases_excluded") int casesExcluded,
            @JsonProperty("assistant_model") String assistantModel,
            @JsonProperty("embedding_model") String embeddingModel, List<RunListResponse.PromptPart> prompts,
            JudgePart judge, EvaluatorPart evaluator,
            @JsonProperty("retrieval_config") RetrievalPart retrievalConfig) {
    }

    /** The judge of a quality run and its settings. */
    public record JudgePart(String model, @JsonProperty("underlying_model") String underlyingModel,
            Double temperature, Integer seed, @JsonProperty("max_input_chars") Integer maxInputChars) {
    }

    /** The evaluation library of a quality run. */
    public record EvaluatorPart(String library, @JsonProperty("library_version") String libraryVersion,
            @JsonProperty("judgment_instructions_version") String judgmentInstructionsVersion) {
    }

    /** How the assistant retrieved context; {@code depth} and {@code ks} only exist in a retrieval run. */
    public record RetrievalPart(@JsonProperty("top_k") Integer topK,
            @JsonProperty("similarity_threshold") Double similarityThreshold,
            @JsonProperty("max_context_characters") Integer maxContextCharacters, Integer depth,
            List<Integer> ks) {
    }

    /** The summary of the run in a scope: {@code all} or a category. */
    public record ScopePart(String scope, int cases, Map<String, RunListResponse.MetricPart> metrics) {
    }

    /** What a case gave, with the case as it was when it was measured. */
    public record ResultPart(@JsonProperty("case_id") String caseId, String revision, String category,
            String question, @JsonProperty("reference_answer") String referenceAnswer,
            @JsonProperty("expected_documents") List<String> expectedDocuments,
            @JsonProperty("case_kind") String caseKind, Map<String, Double> scores,
            @JsonProperty("retrieved_documents") List<String> retrievedDocuments, Boolean abstained,
            @JsonProperty("trace_id") String traceId, @JsonProperty("trace_url") String traceUrl) {
    }
}
