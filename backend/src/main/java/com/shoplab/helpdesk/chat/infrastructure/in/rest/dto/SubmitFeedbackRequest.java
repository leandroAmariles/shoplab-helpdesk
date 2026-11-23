package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

/** Request body for {@code PUT /api/chat/feedback/{turnId}}. */
public record SubmitFeedbackRequest(String rating, String comment) {
}
