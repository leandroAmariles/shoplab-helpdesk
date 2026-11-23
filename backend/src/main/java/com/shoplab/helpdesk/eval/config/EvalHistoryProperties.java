package com.shoplab.helpdesk.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the reading of the history under {@code shoplab.eval.history}.
 *
 * @param traceUrlTemplate the address of a Langfuse trace with {@code {traceId}} where its id goes; empty means the
 *                         screen shows the id instead of a link
 */
@ConfigurationProperties("shoplab.eval.history")
public record EvalHistoryProperties(String traceUrlTemplate) {

    public EvalHistoryProperties {
        traceUrlTemplate = traceUrlTemplate == null ? "" : traceUrlTemplate.strip();
    }
}
