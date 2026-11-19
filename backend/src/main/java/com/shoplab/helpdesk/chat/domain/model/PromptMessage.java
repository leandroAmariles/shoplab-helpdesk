package com.shoplab.helpdesk.chat.domain.model;

import java.util.Set;

/**
 * A message of the prompt sent to the model, in the order it was sent.
 *
 * @param role    {@code system}, {@code user} or {@code assistant}
 * @param content text of the message
 */
public record PromptMessage(String role, String content) {

    private static final Set<String> ROLES = Set.of("system", "user", "assistant");

    public PromptMessage {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("The role of a prompt message must be system, user or assistant");
        }
        if (content == null) {
            throw new IllegalArgumentException("A prompt message needs content");
        }
    }
}
