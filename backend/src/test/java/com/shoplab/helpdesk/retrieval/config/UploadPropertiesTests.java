package com.shoplab.helpdesk.retrieval.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class UploadPropertiesTests {

    @Test
    void theDefaultIsTheLimitOfTheIngestService() {
        assertThat(new UploadProperties(null).maxBytes()).isEqualTo(10_485_760);
    }

    @Test
    void bindsTheConfiguredLimit() {
        Binder binder = new Binder(new MapConfigurationPropertySource(
                java.util.Map.of("shoplab.retrieval.upload.max-bytes", "2048")));

        UploadProperties properties = binder.bind("shoplab.retrieval.upload", UploadProperties.class).get();

        assertThat(properties.maxBytes()).isEqualTo(2048);
    }

    @Test
    void aLimitBelowOneStopsTheStartWithAClearMessage() {
        assertThatThrownBy(() -> new UploadProperties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-bytes must be at least 1");
    }
}
