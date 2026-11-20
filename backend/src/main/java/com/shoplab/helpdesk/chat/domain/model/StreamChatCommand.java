package com.shoplab.helpdesk.chat.domain.model;

/**
 * Input of the chat use case: the customer's text, the correlation id of the request, when the client sends one the
 * identifier of the conversation as it came (it is validated by the use case, not by the controller) and, only for an
 * evaluation, the prompt versions the request fixes (LAB-85).
 */
public record StreamChatCommand(String message, String requestId, String conversationId,
        PromptOverrides promptOverrides) {

    public StreamChatCommand {
        promptOverrides = promptOverrides == null ? PromptOverrides.NONE : promptOverrides;
    }

    /** A request that fixes no prompt version: every prompt is the one labelled for production. */
    public StreamChatCommand(String message, String requestId, String conversationId) {
        this(message, requestId, conversationId, PromptOverrides.NONE);
    }

    /** A request without conversation: it is answered on its own, as before the memory existed. */
    public StreamChatCommand(String message, String requestId) {
        this(message, requestId, null);
    }
}
