package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.CaseComparisonStatus;

/**
 * One case of the union of the two runs. The values and the delta are only set when the case can be compared (worse,
 * better or same); in the other states nothing is claimed about them.
 */
public record CaseComparison(String caseId, String category, String question, CaseComparisonStatus status,
        Double baseValue, Double targetValue, Double delta) {
}
