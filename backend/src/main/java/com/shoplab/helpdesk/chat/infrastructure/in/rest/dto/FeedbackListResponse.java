package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import java.util.List;

/** Response body for {@code GET /api/chat/feedback}. */
public record FeedbackListResponse(
        int total,
        int pendingCount,
        int promotedCount,
        String suggestedNextCaseId,
        List<FeedbackItemResponse> items) {
}
