package com.shoplab.helpdesk.chat.domain.model.enums;

/**
 * Why a version of a prompt cannot be used. The {@link #reason()} goes to the warning and to the metric, so it never
 * carries text of the prompt.
 */
public enum PromptRejection {

    NOT_TEXT("not-text"),
    BLANK("blank"),
    TOO_LONG("too-long"),
    MISSING_PLACEHOLDER("missing-placeholder"),
    NOT_RENDERABLE("not-renderable");

    private final String reason;

    PromptRejection(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
