package com.shoplab.helpdesk.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat settings under {@code shoplab.chat}.
 *
 * @param maxMessageLength longest message accepted, in characters
 * @param systemPrompt     fixed system prompt sent to the model
 */
@ConfigurationProperties("shoplab.chat")
public record ChatProperties(Integer maxMessageLength, String systemPrompt) {

    private static final int DEFAULT_MAX_MESSAGE_LENGTH = 4000;
    private static final String DEFAULT_SYSTEM_PROMPT =
            "Eres el asistente de la mesa de ayuda de una tienda online. Responde de forma breve y amable.";

    public ChatProperties {
        maxMessageLength = maxMessageLength == null ? DEFAULT_MAX_MESSAGE_LENGTH : maxMessageLength;
        systemPrompt = systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }
}
