package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

/**
 * Body of {@code POST /api/chat}.
 *
 * @param message        what the customer writes
 * @param conversationId optional UUID of the conversation; without it the message is answered on its own
 */
public record ChatRequest(String message, String conversationId) {
}
