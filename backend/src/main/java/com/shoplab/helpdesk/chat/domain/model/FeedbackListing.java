package com.shoplab.helpdesk.chat.domain.model;

import java.util.List;

/** Listing of feedback items with summary counters and the next suggested golden case identifier. */
public record FeedbackListing(
        int total,
        int pendingCount,
        int promotedCount,
        String suggestedNextCaseId,
        List<FeedbackItem> items) {

    public FeedbackListing {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
