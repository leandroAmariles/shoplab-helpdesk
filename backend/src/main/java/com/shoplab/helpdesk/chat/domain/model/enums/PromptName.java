package com.shoplab.helpdesk.chat.domain.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The four texts the chat sends to the model (LAB-85). Each one has a stable name in Langfuse, the markers the system
 * fills in (without them the prompt cannot be used) and a default text in the resources.
 */
public enum PromptName {

    /** Rules of every grounded answer. It is sent as is: its braces are literal. */
    CHAT_SYSTEM("chat-system", List.of()),
    /** Instructions of the courtesy answer; {@code {topics}} is replaced by what the assistant can help with. */
    SMALL_TALK("chat-small-talk", List.of("{topics}")),
    /** User message with the retrieved fragments; rendered with {@code query} and {@code context}. */
    CONTEXT("chat-context", List.of("{query}", "{context}")),
    /** User message without fragments (only with {@code no-context-mode=model}); {@code {query}} is replaced. */
    EMPTY_CONTEXT("chat-empty-context", List.of("{query}"));

    private static final String RESOURCE_FOLDER = "prompts/";
    private static final String RESOURCE_EXTENSION = ".txt";
    private static final String METADATA_PREFIX = "prompt_";

    private final String langfuseName;
    private final List<String> requiredPlaceholders;

    PromptName(String langfuseName, List<String> requiredPlaceholders) {
        this.langfuseName = langfuseName;
        this.requiredPlaceholders = requiredPlaceholders;
    }

    /** The name of the prompt in Langfuse. */
    public String langfuseName() {
        return langfuseName;
    }

    /** The markers a version must contain to be usable, as they are written in the text (with braces). */
    public List<String> requiredPlaceholders() {
        return requiredPlaceholders;
    }

    /** The classpath location of the default text. */
    public String resourcePath() {
        return RESOURCE_FOLDER + langfuseName + RESOURCE_EXTENSION;
    }

    /** The name of the trace metadata that records the version used: {@code prompt_chat_system}. */
    public String traceMetadataKey() {
        return METADATA_PREFIX + langfuseName.replace('-', '_');
    }

    public static Optional<PromptName> fromLangfuseName(String value) {
        return Arrays.stream(values()).filter(name -> name.langfuseName.equals(value)).findFirst();
    }
}
