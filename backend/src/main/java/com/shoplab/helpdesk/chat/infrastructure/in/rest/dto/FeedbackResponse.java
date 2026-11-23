package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import java.time.Instant;

/** Response body for {@code PUT /api/chat/feedback/{turnId}}. */
public record FeedbackResponse(
        String turnId,
        String rating,
        String comment,
        Instant createdAt,
        Instant updatedAt,
        String promotedCaseId,
        Instant promotedAt) {
}
