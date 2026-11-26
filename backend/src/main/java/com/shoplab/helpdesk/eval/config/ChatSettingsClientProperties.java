package com.shoplab.helpdesk.eval.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the HTTP client that reads {@code GET /api/assistant/settings} from {@code chat-service}. */
@ConfigurationProperties(prefix = "shoplab.chat.settings.client")
public record ChatSettingsClientProperties(String mode, String baseUrl, Duration timeout) {

    private static final String DEFAULT_MODE = "in-process";
    private static final String DEFAULT_BASE_URL = "http://localhost:8083";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public ChatSettingsClientProperties {
        mode = (mode == null || mode.isBlank()) ? DEFAULT_MODE : mode;
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        timeout = (timeout == null || timeout.isNegative() || timeout.isZero()) ? DEFAULT_TIMEOUT : timeout;
    }
}
