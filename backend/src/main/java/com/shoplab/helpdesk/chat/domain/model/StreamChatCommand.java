package com.shoplab.helpdesk.chat.domain.model;

/** Input of the chat use case: the customer's text and the correlation id of the request. */
public record StreamChatCommand(String message, String requestId) {
}
