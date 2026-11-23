package com.shoplab.helpdesk.eval.domain.model;

import java.util.Map;

/**
 * The summary of a run in a scope: {@code all} or a category of the golden dataset.
 *
 * @param cases the cases of the scope that were measured
 */
public record ScopeSummary(String scope, int cases, Map<String, MetricStat> metrics) {
}
