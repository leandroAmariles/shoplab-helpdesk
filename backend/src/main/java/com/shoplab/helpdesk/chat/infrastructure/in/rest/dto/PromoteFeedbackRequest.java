package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import java.util.List;

/** Request body for {@code POST /api/chat/feedback/{turnId}/promote}. */
public record PromoteFeedbackRequest(
        String caseId,
        String question,
        String referenceAnswer,
        List<String> expectedDocuments,
        String category) {
}
