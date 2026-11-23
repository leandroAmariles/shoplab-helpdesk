package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import java.util.List;
import java.util.Map;

/**
 * What a case gave in a run, with the case as it was when it was measured. {@code revision} is the hash of that
 * revision of the case. A {@code null} score means the metric does not apply or cannot be computed by definition. The
 * text of the answer and of the fragments is never part of it.
 */
public record StoredResult(String caseId, String revision, String category, String question, String referenceAnswer,
        List<String> expectedDocuments, CaseKind caseKind, Map<String, Double> scores,
        List<String> retrievedDocuments, Boolean abstained, String traceId) {
}
