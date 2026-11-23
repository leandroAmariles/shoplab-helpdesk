package com.shoplab.helpdesk.chat.domain.model;

import java.util.List;

/** Proposed golden dataset case to promote from a negative feedback turn. */
public record GoldenCaseDraft(
        String caseId,
        String question,
        String referenceAnswer,
        List<String> expectedDocuments,
        String category) {

    public GoldenCaseDraft {
        expectedDocuments = expectedDocuments == null ? List.of() : List.copyOf(expectedDocuments);
    }
}
