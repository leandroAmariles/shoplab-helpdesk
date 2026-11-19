package com.shoplab.helpdesk.chat.domain.model;

import java.util.OptionalInt;

/**
 * Tokens used by a generation. A {@code null} count means the model did not report it: unknown is not zero, and the
 * trace shows it as such (LAB-64).
 *
 * @param inputTokens  tokens of the prompt, or {@code null} when unknown (never negative)
 * @param outputTokens tokens of the answer, or {@code null} when unknown (never negative)
 */
public record TokenUsage(Integer inputTokens, Integer outputTokens) {

    public TokenUsage {
        if ((inputTokens != null && inputTokens < 0) || (outputTokens != null && outputTokens < 0)) {
            throw new IllegalArgumentException("The tokens of a usage must not be negative");
        }
    }

    public static TokenUsage unknown() {
        return new TokenUsage(null, null);
    }

    /** Both counts were reported. */
    public boolean isKnown() {
        return inputTokens != null && outputTokens != null;
    }

    /** The sum of both counts, empty when one of them is unknown. */
    public OptionalInt total() {
        return isKnown() ? OptionalInt.of(inputTokens + outputTokens) : OptionalInt.empty();
    }

    /** Adds the usage of another round of the same turn; a side that is unknown in both stays unknown. */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(sum(inputTokens, other.inputTokens), sum(outputTokens, other.outputTokens));
    }

    private static Integer sum(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : left + right;
    }
}
