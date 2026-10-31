package com.shoplab.helpdesk.retrieval.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the call to the ingest service under {@code shoplab.retrieval.ingest}.
 *
 * @param baseUrl          address of the ingest service
 * @param timeout          longest wait for its answer
 * @param maxResponseBytes largest answer accepted, in bytes (a big document yields thousands of chunks)
 */
@ConfigurationProperties("shoplab.retrieval.ingest")
public record IngestClientProperties(String baseUrl, Duration timeout, Integer maxResponseBytes) {

    private static final String DEFAULT_BASE_URL = "http://localhost:8000";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    public IngestClientProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        maxResponseBytes = maxResponseBytes == null ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }
}
