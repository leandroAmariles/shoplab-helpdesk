package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The runs of a kind, oldest first, with the whole-set metrics of each (contracts/eval-history-api.md). A metric
 * nobody could compute is {@code {"mean": null, "n": 0}}; one the run does not have is not in {@code metrics}.
 */
public record RunListResponse(String kind, int shown, long total,
        @JsonProperty("total_all_origins") long totalAllOrigins, List<OriginPart> origins, List<RunPart> runs) {

    /** The runs of the kind that come from an origin. */
    public record OriginPart(String origin, long count) {
    }

    /** A run in the list. */
    public record RunPart(@JsonProperty("run_id") String runId, String kind,
            @JsonProperty("started_at") Instant startedAt, @JsonProperty("finished_at") Instant finishedAt,
            String origin, @JsonProperty("commit_sha") String commitSha,
            @JsonProperty("assistant_model") String assistantModel, List<PromptPart> prompts,
            @JsonProperty("cases_measured") int casesMeasured, Map<String, MetricPart> metrics) {
    }

    /** A prompt and the version used; {@code version} is {@code null} for the packaged default text. */
    public record PromptPart(String name, Integer version, String source, boolean pinned) {
    }

    /** The mean of a metric over the cases that have a value and how many they are. */
    public record MetricPart(Double mean, int n) {
    }
}
