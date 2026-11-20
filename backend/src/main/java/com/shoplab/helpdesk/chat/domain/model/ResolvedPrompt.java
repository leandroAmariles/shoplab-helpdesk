package com.shoplab.helpdesk.chat.domain.model;

/**
 * The text of a prompt as a turn uses it, with the reference that tells where it came from.
 *
 * @param text      what is sent to the model; never blank
 * @param reference name, version and source of the text
 */
public record ResolvedPrompt(String text, PromptReference reference) {

    public ResolvedPrompt {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The text of a prompt must not be blank");
        }
        if (reference == null) {
            throw new IllegalArgumentException("A resolved prompt needs its reference");
        }
    }
}
