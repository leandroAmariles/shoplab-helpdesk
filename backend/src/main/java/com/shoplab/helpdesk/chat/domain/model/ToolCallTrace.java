package com.shoplab.helpdesk.chat.domain.model;

/**
 * A tool the model called during a turn.
 *
 * @param name         name of the tool
 * @param arguments    arguments as the tool received them (empty when none)
 * @param result       result, {@code null} when the call failed
 * @param failed       the call ended with an error
 * @param errorMessage short description of the error, {@code null} when it did not fail
 */
public record ToolCallTrace(String name, String arguments, String result, boolean failed, String errorMessage) {

    public ToolCallTrace {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A tool call needs the name of the tool");
        }
        arguments = arguments == null ? "" : arguments;
    }
}
