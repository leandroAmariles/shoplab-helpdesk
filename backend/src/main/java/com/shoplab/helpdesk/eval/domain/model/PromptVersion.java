package com.shoplab.helpdesk.eval.domain.model;

import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;

/**
 * One prompt of the assistant as an evaluation used it.
 *
 * @param version the numbered version of Langfuse, or {@code null} when the text is the packaged default
 * @param pinned  {@code true} when the evaluation fixed the version in its requests instead of using {@code production}
 */
public record PromptVersion(String name, Integer version, PromptSource source, Boolean pinned) {
}
