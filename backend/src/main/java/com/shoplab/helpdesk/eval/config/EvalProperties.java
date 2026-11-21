package com.shoplab.helpdesk.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the evaluation history under {@code shoplab.eval.persistence}.
 *
 * @param token the value the evaluation tool sends in {@code X-Eval-Token}; empty means saving is off
 */
@ConfigurationProperties("shoplab.eval.persistence")
public record EvalProperties(String token) {

    public EvalProperties {
        token = token == null ? "" : token.strip();
    }
}
