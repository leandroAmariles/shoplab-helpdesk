package com.shoplab.helpdesk.chat.api;

/**
 * A prompt as the assistant serves it now.
 *
 * @param name    the name of the prompt in Langfuse
 * @param version the numbered version, or {@code null} when the text is the default one packaged with the backend
 * @param source  {@code LANGFUSE} or {@code DEFAULT}
 */
public record ServedPrompt(String name, Integer version, String source) {
}
