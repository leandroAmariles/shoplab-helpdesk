package com.shoplab.helpdesk.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the model gateway under {@code shoplab.chat.gateway}. The address and the virtual key live in
 * {@code spring.ai.openai.*}; the backend only ever asks for logical model names.
 *
 * @param primaryModel   the name under which the gateway reports an answer of its primary model (its model group,
 *                       {@code chat}); an answer reported under another name came from the fallback model, which the
 *                       trace marks. Blank means unknown and no turn is ever marked as fallback
 * @param requestTimeout the longest the backend waits for the gateway to answer (greater than the gateway's own)
 */
@ConfigurationProperties("shoplab.chat.gateway")
public record GatewayProperties(String primaryModel, Duration requestTimeout) {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public GatewayProperties {
        primaryModel = primaryModel == null ? "" : primaryModel.strip();
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "shoplab.chat.gateway.request-timeout must be positive but was " + requestTimeout);
        }
    }
}
