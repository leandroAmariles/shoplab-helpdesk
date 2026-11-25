package com.shoplab.helpdesk.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where {@code retrieval-service} is and how long the chat waits for it, under {@code shoplab.retrieval.client}.
 *
 * @param baseUrl address of the service (default the one it has in the local compose)
 * @param timeout longest wait for a search (default 15 seconds, which covers the first load of the embedding model)
 */
@ConfigurationProperties("shoplab.retrieval.client")
public record RetrievalClientProperties(String baseUrl, Duration timeout) {

    private static final String DEFAULT_BASE_URL = "http://localhost:8082";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    public RetrievalClientProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("shoplab.retrieval.client.timeout must be positive but was " + timeout);
        }
    }
}
