package com.shoplab.helpdesk.eval.domain.model;

import java.util.List;

/**
 * A case of the golden dataset as it was when it was measured. {@code contentHash} identifies this revision of the case;
 * the backend computes it, so it is {@code null} until then.
 */
public record EvalCase(String caseId, String contentHash, String category, String question, String referenceAnswer,
        List<String> expectedDocuments) {

    public EvalCase withContentHash(String hash) {
        return new EvalCase(caseId, hash, category, question, referenceAnswer, expectedDocuments);
    }
}
