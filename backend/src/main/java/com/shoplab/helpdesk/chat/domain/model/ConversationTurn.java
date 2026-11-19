package com.shoplab.helpdesk.chat.domain.model;

/**
 * One exchange of a conversation: what the customer asked and the whole answer. Only the texts: no sources and no
 * knowledge-base context, because each turn retrieves its own.
 *
 * @param question the customer's message, as sent
 * @param answer   the complete answer of the assistant, as it was streamed
 */
public record ConversationTurn(String question, String answer) {

    public ConversationTurn {
        if (question == null || question.isEmpty() || answer == null || answer.isEmpty()) {
            throw new IllegalArgumentException("A conversation turn needs a question and an answer");
        }
    }
}
