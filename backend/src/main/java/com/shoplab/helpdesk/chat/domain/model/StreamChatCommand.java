package com.shoplab.helpdesk.chat.domain.model;

/**
 * Input of the chat use case: the customer's text, the correlation id of the request and, when the client sends one,
 * the identifier of the conversation as it came (it is validated by the use case, not by the controller).
 */
public record StreamChatCommand(String message, String requestId, String conversationId) {

    /** A request without conversation: it is answered on its own, as before the memory existed. */
    public StreamChatCommand(String message, String requestId) {
        this(message, requestId, null);
    }
}
