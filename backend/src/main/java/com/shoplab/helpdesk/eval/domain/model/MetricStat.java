package com.shoplab.helpdesk.eval.domain.model;

/**
 * The value of a metric in a scope: the mean of the cases that have a value, and how many they are. {@code mean} is
 * {@code null} (and {@code n} is {@code 0}) when no case has a value; that is a gap, never a zero.
 */
public record MetricStat(Double mean, int n) {
}
