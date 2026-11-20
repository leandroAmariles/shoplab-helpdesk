package com.shoplab.helpdesk.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat settings under {@code shoplab.chat}. The text of the prompts is not here: it is managed in Langfuse and its
 * default lives in {@code prompts/*.txt} (LAB-85, see {@link PromptProperties}).
 *
 * @param maxMessageLength longest message accepted, in characters
 */
@ConfigurationProperties("shoplab.chat")
public record ChatProperties(Integer maxMessageLength) {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 4000;

    public ChatProperties {
        maxMessageLength = maxMessageLength == null ? DEFAULT_MAX_MESSAGE_LENGTH : maxMessageLength;
    }
}
