package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A run in the list and in the chart. {@code metrics} holds the whole-set value of each metric the run has; a metric
 * the run does not have is not in the map.
 */
public record RunListItem(String runId, RunKind kind, Instant startedAt, Instant finishedAt, String origin,
        String commitSha, String assistantModel, List<PromptVersion> prompts, int casesMeasured,
        Map<String, MetricStat> metrics) {
}
