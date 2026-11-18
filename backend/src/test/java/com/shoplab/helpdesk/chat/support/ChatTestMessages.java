package com.shoplab.helpdesk.chat.support;

/**
 * Messages the chat tests send as "any question". A greeting such as "Hola" is no longer a question: since LAB-114 it
 * is answered without retrieval, so the tests that exercise the question flow use this one.
 */
public final class ChatTestMessages {

    /** A real question of the store: never a courtesy. */
    public static final String QUESTION = "¿Cuánto tarda un envío?";

    private ChatTestMessages() {
    }
}
