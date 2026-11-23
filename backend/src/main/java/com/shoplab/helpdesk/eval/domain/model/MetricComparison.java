package com.shoplab.helpdesk.eval.domain.model;

/** The whole-set value of a metric in both runs. {@code delta} is {@code target - base}, {@code null} without both. */
public record MetricComparison(String metric, MetricStat base, MetricStat target, Double delta) {
}
