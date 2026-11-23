package com.shoplab.helpdesk.eval.domain.model;

/**
 * A parameter that is not the same in the two runs. A {@code null} side is a value that is not known.
 *
 * @param affectsComparability {@code true} when the difference changes what is measured (judge, models, retrieval,
 *                             dataset), {@code false} for what is normally being tested (prompt, commit, origin)
 */
public record ParameterDifference(String field, String base, String target, boolean affectsComparability) {
}
