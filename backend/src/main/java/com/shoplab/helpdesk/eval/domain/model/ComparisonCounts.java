package com.shoplab.helpdesk.eval.domain.model;

/** How many cases of the union fall in each state of the comparison. */
public record ComparisonCounts(int worse, int better, int same, int noValue, int notComparable, int onlyBase,
        int onlyTarget) {
}
