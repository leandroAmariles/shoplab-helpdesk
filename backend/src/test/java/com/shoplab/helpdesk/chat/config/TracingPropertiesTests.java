package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.config.TracingProperties.Price;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracingPropertiesTests {

    private static TracingProperties withDefaults() {
        return new TracingProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        TracingProperties properties = withDefaults();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.endpoint()).isEqualTo("http://localhost:3001/api/public/otel");
        assertThat(properties.environment()).isEqualTo("local");
        assertThat(properties.maxContentCharacters()).isEqualTo(20_000);
        assertThat(properties.queueSize()).isEqualTo(2048);
        assertThat(properties.batchSize()).isEqualTo(256);
        assertThat(properties.scheduleDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.exportTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.pricing()).containsOnlyKeys("ollama");
        assertThat(properties.pricing().get("ollama")).isEqualTo(new Price(0.0, 0.0));
        assertThat(properties.hasKeys()).isFalse();
    }

    @Test
    void sizesMustBePositive() {
        assertThatThrownBy(() -> new TracingProperties(true, null, null, null, null, 0, null, null, null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TracingProperties(true, null, null, null, null, null, 0, null, null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TracingProperties(true, null, null, null, null, null, null, 0, null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBatchMustFitInTheQueue() {
        assertThatThrownBy(() -> new TracingProperties(true, null, null, null, null, null, 10, 11, null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("batch-size");
    }

    @Test
    void pricesMustNotBeNegative() {
        assertThatThrownBy(() -> new Price(-0.1, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(0.0, -1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(null, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Price(3.0, 15.0).inputPerMillion()).isEqualTo(3.0);
    }

    @Test
    void thePricingYouGiveReplacesTheDefault() {
        TracingProperties properties = new TracingProperties(null, null, null, null, null, null, null, null, null,
                null, null, Map.of("gpt-x", new Price(1.0, 2.0)));

        assertThat(properties.pricing()).containsOnlyKeys("gpt-x");
    }

    @Test
    void theSecretKeyNeverAppearsInTheTextOfTheProperties() {
        TracingProperties properties = new TracingProperties(true, null, "pk-public", "sk-very-secret", null, null,
                null, null, null, null, null, null);

        assertThat(properties.hasKeys()).isTrue();
        assertThat(properties.toString()).doesNotContain("sk-very-secret").doesNotContain("pk-public");
    }
}
