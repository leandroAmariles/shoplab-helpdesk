package com.shoplab.helpdesk.chat.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the LLM traces under {@code shoplab.chat.tracing} (LAB-64). The tracing is off by default: when it is
 * off nothing is created and no connection is opened.
 *
 * @param enabled              sends one trace per chat turn to Langfuse (default false)
 * @param endpoint             base OTLP address of Langfuse, without {@code /v1/traces}
 * @param publicKey            public key of the Langfuse project
 * @param secretKey            secret key of the Langfuse project; never printed
 * @param environment          label of the environment shown in the traces (default {@code local})
 * @param maxContentCharacters most characters of each text attribute (question, prompt, answer...), at least 1
 * @param queueSize            traces waiting to be sent; when it is full, traces are dropped, at least 1
 * @param batchSize            traces per delivery, at least 1 and at most {@code queueSize}
 * @param scheduleDelay        longest wait before a delivery
 * @param exportTimeout        longest time of one delivery
 * @param shutdownTimeout      longest time to deliver what is pending when the application stops
 * @param pricing              price per million tokens, by model name or by provider (default {@code ollama} at 0)
 */
@ConfigurationProperties("shoplab.chat.tracing")
public record TracingProperties(Boolean enabled, String endpoint, String publicKey, String secretKey,
        String environment, Integer maxContentCharacters, Integer queueSize, Integer batchSize,
        Duration scheduleDelay, Duration exportTimeout, Duration shutdownTimeout, Map<String, Price> pricing) {

    private static final String DEFAULT_ENDPOINT = "http://localhost:3001/api/public/otel";
    private static final String DEFAULT_ENVIRONMENT = "local";
    private static final int DEFAULT_MAX_CONTENT_CHARACTERS = 20_000;
    private static final int DEFAULT_QUEUE_SIZE = 2048;
    private static final int DEFAULT_BATCH_SIZE = 256;

    /**
     * Price of a model in USD per million tokens.
     *
     * @param inputPerMillion  price of one million prompt tokens (not negative)
     * @param outputPerMillion price of one million answer tokens (not negative)
     */
    public record Price(Double inputPerMillion, Double outputPerMillion) {

        public Price {
            if (inputPerMillion == null || outputPerMillion == null || inputPerMillion < 0 || outputPerMillion < 0) {
                throw new IllegalArgumentException(
                        "shoplab.chat.tracing.pricing needs an input and an output price, both at least 0");
            }
        }
    }

    public TracingProperties {
        enabled = enabled != null && enabled;
        endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.strip();
        publicKey = publicKey == null ? "" : publicKey.strip();
        secretKey = secretKey == null ? "" : secretKey.strip();
        environment = environment == null || environment.isBlank() ? DEFAULT_ENVIRONMENT : environment.strip();
        maxContentCharacters = maxContentCharacters == null ? DEFAULT_MAX_CONTENT_CHARACTERS : maxContentCharacters;
        queueSize = queueSize == null ? DEFAULT_QUEUE_SIZE : queueSize;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        scheduleDelay = scheduleDelay == null ? Duration.ofSeconds(2) : scheduleDelay;
        exportTimeout = exportTimeout == null ? Duration.ofSeconds(5) : exportTimeout;
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(5) : shutdownTimeout;
        pricing = pricing == null ? Map.of("ollama", new Price(0.0, 0.0)) : Map.copyOf(pricing);
        if (maxContentCharacters < 1 || queueSize < 1 || batchSize < 1) {
            throw new IllegalArgumentException(
                    "shoplab.chat.tracing: max-content-characters, queue-size and batch-size must be at least 1");
        }
        if (batchSize > queueSize) {
            throw new IllegalArgumentException("shoplab.chat.tracing.batch-size must not exceed queue-size");
        }
    }

    /** Both keys are there, so a trace can be authenticated. */
    public boolean hasKeys() {
        return !publicKey.isEmpty() && !secretKey.isEmpty();
    }

    /** The secret key must not reach a log through the record. */
    @Override
    public String toString() {
        return "TracingProperties[enabled=" + enabled + ", endpoint=" + endpoint + ", environment=" + environment
                + ", keys=" + (hasKeys() ? "set" : "missing") + "]";
    }
}
