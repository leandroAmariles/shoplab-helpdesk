package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import java.time.Instant;
import java.util.List;

/** Item in {@code GET /api/chat/feedback} and response of {@code POST /api/chat/feedback/{turnId}/promote}. */
public record FeedbackItemResponse(
        String turnId,
        String conversationId,
        String question,
        String answer,
        List<String> deliveredDocuments,
        String traceId,
        String rating,
        String comment,
        Instant createdAt,
        Instant updatedAt,
        String promotedCaseId,
        String promotedCaseJsonl,
        Instant promotedAt) {
}
