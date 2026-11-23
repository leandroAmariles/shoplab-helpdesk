package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;

/** Command to record or update the customer's feedback for a completed chat turn. */
public record SubmitFeedbackCommand(String turnId, FeedbackRating rating, String comment) {
}
