package com.shoplab.helpdesk.eval.domain.model;

import java.math.BigDecimal;
import java.util.List;

/** Two runs of the same kind compared for one metric with a tolerance {@code drop} (specs/LAB-32). */
public record RunComparison(RunHeader base, RunHeader target, String metric, BigDecimal drop,
        List<MetricComparison> metrics, List<ParameterDifference> parameters, List<String> warnings,
        ComparisonCounts counts, List<CaseComparison> cases) {
}
