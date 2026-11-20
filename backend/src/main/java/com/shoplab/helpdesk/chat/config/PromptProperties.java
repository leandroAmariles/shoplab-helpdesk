package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the managed prompts under {@code shoplab.chat.prompts} (LAB-85). The management is off by default: with
 * it off the chat always uses the default text of each prompt and no client, thread or connection is created.
 *
 * @param enabled             loads the prompts from Langfuse (default false); independent of the tracing
 * @param baseUrl             address of Langfuse, without {@code /api/...}
 * @param publicKey           public key of the Langfuse project
 * @param secretKey           secret key of the Langfuse project; never printed
 * @param label               label whose version is served (default {@code production})
 * @param refreshInterval     how often the prompts are asked again; it is also the longest time a change of label takes
 *                            to reach the chat (at least 5 seconds, default 60)
 * @param requestTimeout      longest time of each request to Langfuse (from 100 ms to 30 s, default 2 s)
 * @param maxPromptCharacters largest prompt accepted (at least 100, default 20000)
 * @param publishMissing      creates in Langfuse the prompts that do not exist, with their default text (default false)
 * @param overrideToken       token that lets the evaluation fix prompt versions in a request; blank turns it off
 */
@ConfigurationProperties("shoplab.chat.prompts")
public record PromptProperties(Boolean enabled, String baseUrl, String publicKey, String secretKey, String label,
        Duration refreshInterval, Duration requestTimeout, Integer maxPromptCharacters, Boolean publishMissing,
        String overrideToken) {

    private static final String DEFAULT_BASE_URL = "http://localhost:3001";
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(60);
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MIN_REQUEST_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_PROMPT_CHARACTERS = 20_000;
    private static final int MIN_MAX_PROMPT_CHARACTERS = 100;

    public PromptProperties {
        enabled = enabled != null && enabled;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl.strip());
        publicKey = publicKey == null ? "" : publicKey.strip();
        secretKey = secretKey == null ? "" : secretKey.strip();
        label = label == null || label.isBlank() ? PromptConstants.DEFAULT_LABEL : label.strip();
        refreshInterval = refreshInterval == null ? DEFAULT_REFRESH_INTERVAL : refreshInterval;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        maxPromptCharacters = maxPromptCharacters == null ? DEFAULT_MAX_PROMPT_CHARACTERS : maxPromptCharacters;
        publishMissing = publishMissing != null && publishMissing;
        overrideToken = overrideToken == null ? "" : overrideToken.strip();
        if (enabled) {
            requireValid(baseUrl, refreshInterval, requestTimeout, maxPromptCharacters);
        }
    }

    /** Both keys are there, so Langfuse can be asked. */
    public boolean hasKeys() {
        return !publicKey.isEmpty() && !secretKey.isEmpty();
    }

    /** The prompts are loaded from Langfuse: they are enabled and Langfuse can be authenticated. */
    public boolean active() {
        return enabled && hasKeys();
    }

    /**
     * A request may fix prompt versions only when the prompts are managed and a token is configured; otherwise the
     * capability does not exist and the header is answered with 403.
     */
    public boolean overrideEnabled() {
        return enabled && !overrideToken.isEmpty();
    }

    /** The keys and the token must not reach a log through the record. */
    @Override
    public String toString() {
        return "PromptProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", label=" + label
                + ", refreshInterval=" + refreshInterval + ", requestTimeout=" + requestTimeout
                + ", publishMissing=" + publishMissing + ", keys=" + (hasKeys() ? "set" : "missing")
                + ", override-token=" + (overrideToken.isEmpty() ? "unset" : "set") + "]";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void requireValid(String baseUrl, Duration refreshInterval, Duration requestTimeout,
            int maxPromptCharacters) {
        try {
            URI uri = URI.create(baseUrl);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("not an absolute address");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("shoplab.chat.prompts.base-url must be an address such as "
                    + DEFAULT_BASE_URL);
        }
        if (refreshInterval.compareTo(MIN_REFRESH_INTERVAL) < 0) {
            throw new IllegalArgumentException("shoplab.chat.prompts.refresh-interval must be at least 5s");
        }
        if (requestTimeout.compareTo(MIN_REQUEST_TIMEOUT) < 0 || requestTimeout.compareTo(MAX_REQUEST_TIMEOUT) > 0) {
            throw new IllegalArgumentException("shoplab.chat.prompts.request-timeout must be between 100ms and 30s");
        }
        if (maxPromptCharacters < MIN_MAX_PROMPT_CHARACTERS) {
            throw new IllegalArgumentException("shoplab.chat.prompts.max-prompt-characters must be at least "
                    + MIN_MAX_PROMPT_CHARACTERS);
        }
    }
}
