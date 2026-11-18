package com.shoplab.helpdesk.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat settings under {@code shoplab.chat}.
 *
 * @param maxMessageLength longest message accepted, in characters
 * @param systemPrompt     system prompt sent to the model. Its default lives in {@code application.yml} (the rules that
 *                         make the model answer only with the context, cite, and not reveal itself) and is replaced
 *                         with {@code SHOPLAB_CHAT_SYSTEM_PROMPT}; it can never be blank
 */
@ConfigurationProperties("shoplab.chat")
public record ChatProperties(Integer maxMessageLength, String systemPrompt) {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 4000;

    public ChatProperties {
        maxMessageLength = maxMessageLength == null ? DEFAULT_MAX_MESSAGE_LENGTH : maxMessageLength;
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("shoplab.chat.system-prompt must not be blank: it carries the rules of "
                    + "the answers (the default is in application.yml)");
        }
    }
}
