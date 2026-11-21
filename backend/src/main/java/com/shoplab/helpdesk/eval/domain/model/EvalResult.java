package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import java.util.List;
import java.util.Map;

/**
 * What a case gave in a run. A {@code null} score means the metric does not apply or cannot be computed by definition;
 * a {@code 0.0} is a real zero. The text of the answer and of the fragments is never part of it.
 */
public record EvalResult(EvalCase evalCase, CaseKind caseKind, Map<String, Double> scores,
        List<String> retrievedDocuments, Boolean abstained, String traceId) {
}
