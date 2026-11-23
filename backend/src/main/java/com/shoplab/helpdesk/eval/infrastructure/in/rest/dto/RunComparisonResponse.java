package com.shoplab.helpdesk.eval.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Two runs of the same kind compared for one metric (contracts/eval-history-api.md). A case that cannot be compared
 * says nothing about its values: they are not in the body.
 */
public record RunComparisonResponse(RunPart base, RunPart target, String metric, double drop,
        List<MetricPart> metrics, List<ParameterPart> parameters, List<String> warnings, CountsPart counts,
        List<CasePart> cases) {

    /** The few fields that tell a run apart. */
    public record RunPart(@JsonProperty("run_id") String runId, String kind,
            @JsonProperty("started_at") Instant startedAt, String origin) {
    }

    /** The mean of a metric and how many cases it was computed with. */
    public record StatPart(Double mean, int n) {
    }

    /** A metric in both runs; {@code delta} is {@code null} when either run has no mean for it. */
    public record MetricPart(String metric, StatPart base, StatPart target, Double delta) {
    }

    /** A parameter that differs; a {@code null} side is a value that is not known. */
    public record ParameterPart(String field, String base, String target,
            @JsonProperty("affects_comparability") boolean affectsComparability) {
    }

    /** How many cases fall in each state. */
    public record CountsPart(int worse, int better, int same, @JsonProperty("no_value") int noValue,
            @JsonProperty("not_comparable") int notComparable, @JsonProperty("only_base") int onlyBase,
            @JsonProperty("only_target") int onlyTarget) {
    }

    /** A case of the union of both runs. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CasePart(@JsonProperty("case_id") String caseId, String category, String question, String status,
            @JsonProperty("base_value") Double baseValue, @JsonProperty("target_value") Double targetValue,
            Double delta) {
    }
}
