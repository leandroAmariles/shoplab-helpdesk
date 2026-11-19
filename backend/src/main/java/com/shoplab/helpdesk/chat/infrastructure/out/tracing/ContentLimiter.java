package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;

/** Cuts the texts sent to a trace to a limit, so a huge prompt cannot weigh on the export (specs/LAB-64, FR-018). */
public class ContentLimiter {

    private final int maxCharacters;

    public ContentLimiter(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    /**
     * A text and whether it had to be cut.
     *
     * @param text      the text, at most the limit plus the truncation mark
     * @param truncated the original was longer than the limit
     */
    public record Limited(String text, boolean truncated) {
    }

    public Limited limit(String text) {
        if (text == null) {
            return new Limited("", false);
        }
        if (text.length() <= maxCharacters) {
            return new Limited(text, false);
        }
        int end = maxCharacters;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return new Limited(text.substring(0, end) + TracingConstants.TRUNCATED_SUFFIX, true);
    }
}
