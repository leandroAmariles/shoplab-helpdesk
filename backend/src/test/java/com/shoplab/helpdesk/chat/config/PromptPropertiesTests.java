package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PromptPropertiesTests {

    private static final String PREFIX = "shoplab.chat.prompts.";

    private static PromptProperties bound(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("shoplab.chat.prompts", PromptProperties.class);
    }

    @Test
    void usesTheDocumentedDefaults() {
        PromptProperties properties = bound(Map.of());

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.baseUrl()).isEqualTo("http://localhost:3001");
        assertThat(properties.label()).isEqualTo("production");
        assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.maxPromptCharacters()).isEqualTo(20_000);
        assertThat(properties.publishMissing()).isFalse();
        assertThat(properties.hasKeys()).isFalse();
        assertThat(properties.active()).isFalse();
    }

    @Test
    void bindsTheValuesOfTheYamlAndStripsTheTrailingSlash() {
        PromptProperties properties = bound(Map.of(
                PREFIX + "enabled", "true",
                PREFIX + "base-url", "http://langfuse-web:3000/",
                PREFIX + "public-key", "pk",
                PREFIX + "secret-key", "sk",
                PREFIX + "refresh-interval", "10s",
                PREFIX + "request-timeout", "500ms",
                PREFIX + "publish-missing", "true"));

        assertThat(properties.baseUrl()).isEqualTo("http://langfuse-web:3000");
        assertThat(properties.active()).isTrue();
        assertThat(properties.refreshInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(properties.publishMissing()).isTrue();
    }

    @Test
    void theOverrideCapabilityNeedsTheManagementAndAToken() {
        assertThat(bound(Map.of()).overrideEnabled()).isFalse();
        assertThat(bound(Map.of(PREFIX + "override-token", "t")).overrideEnabled()).isFalse();
        assertThat(bound(Map.of(PREFIX + "enabled", "true")).overrideEnabled()).isFalse();
        assertThat(bound(Map.of(PREFIX + "enabled", "true", PREFIX + "override-token", "t")).overrideEnabled())
                .isTrue();
        assertThat(bound(Map.of(PREFIX + "enabled", "true", PREFIX + "override-token", "  ")).overrideEnabled())
                .isFalse();
    }

    @Test
    void enabledWithoutKeysIsNotActive() {
        PromptProperties properties = bound(Map.of(PREFIX + "enabled", "true"));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.active()).isFalse();
    }

    @Test
    void validatesTheValuesOnlyWhenEnabled() {
        assertThat(bound(Map.of(PREFIX + "refresh-interval", "1s")).enabled()).isFalse();
        assertThatThrownBy(() -> bound(Map.of(PREFIX + "enabled", "true", PREFIX + "refresh-interval", "4s")))
                .hasRootCauseMessage("shoplab.chat.prompts.refresh-interval must be at least 5s");
        assertThatThrownBy(() -> bound(Map.of(PREFIX + "enabled", "true", PREFIX + "request-timeout", "50ms")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bound(Map.of(PREFIX + "enabled", "true", PREFIX + "request-timeout", "31s")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bound(Map.of(PREFIX + "enabled", "true", PREFIX + "max-prompt-characters", "99")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bound(Map.of(PREFIX + "enabled", "true", PREFIX + "base-url", "no es una url")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankLabelFallsBackToProduction() {
        assertThat(bound(Map.of(PREFIX + "label", " ")).label()).isEqualTo("production");
        assertThat(bound(Map.of(PREFIX + "label", "staging")).label()).isEqualTo("staging");
    }

    @Test
    void toStringNeverShowsTheKeysNorTheToken() {
        PromptProperties properties = bound(Map.of(
                PREFIX + "public-key", "pk-secreta-1",
                PREFIX + "secret-key", "sk-secreta-2",
                PREFIX + "override-token", "token-secreto-3"));

        assertThat(properties.toString()).contains("keys=set", "override-token=set")
                .doesNotContain("pk-secreta-1", "sk-secreta-2", "token-secreto-3");
        assertThat(bound(Map.of()).toString()).contains("keys=missing", "override-token=unset");
    }
}
