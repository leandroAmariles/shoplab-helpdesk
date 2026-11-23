package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import java.time.Instant;
import java.util.List;

/** Feedback joined with the full context of its chat turn for review and promotion in admin-eval. */
public record FeedbackItem(
        String turnId,
        String conversationId,
        String question,
        String answer,
        List<String> deliveredDocuments,
        String traceId,
        FeedbackRating rating,
        String comment,
        Instant createdAt,
        Instant updatedAt,
        String promotedCaseId,
        String promotedCaseJsonl,
        Instant promotedAt) {

    public FeedbackItem {
        deliveredDocuments = deliveredDocuments == null ? List.of() : List.copyOf(deliveredDocuments);
    }
}
