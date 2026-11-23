package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import java.time.Instant;

/** Stored feedback record for a single chat turn. */
public record FeedbackRecord(
        String turnId,
        FeedbackRating rating,
        String comment,
        Instant createdAt,
        Instant updatedAt,
        String promotedCaseId,
        Instant promotedAt) {
}
