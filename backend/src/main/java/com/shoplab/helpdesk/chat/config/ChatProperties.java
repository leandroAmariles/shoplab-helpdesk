package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat settings under {@code shoplab.chat}.
 *
 * @param maxMessageLength longest message accepted, in characters
 * @param systemPrompt     system prompt sent to the model; the default makes it answer only with the context
 */
@ConfigurationProperties("shoplab.chat")
public record ChatProperties(Integer maxMessageLength, String systemPrompt) {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 4000;

    public ChatProperties {
        maxMessageLength = maxMessageLength == null ? DEFAULT_MAX_MESSAGE_LENGTH : maxMessageLength;
        systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? ChatConstants.DEFAULT_SYSTEM_PROMPT
                : systemPrompt;
    }
}
