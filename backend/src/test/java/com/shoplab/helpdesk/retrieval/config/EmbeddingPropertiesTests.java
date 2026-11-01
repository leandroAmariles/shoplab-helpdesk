package com.shoplab.helpdesk.retrieval.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.retrieval.domain.model.enums.ReindexMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmbeddingPropertiesTests {

    @Test
    void usesTheDefaultsWhenNothingIsConfigured() {
        EmbeddingProperties properties = new EmbeddingProperties(null, null, null, null);

        assertThat(properties.batchSize()).isEqualTo(16);
        assertThat(properties.searchLimit()).isEqualTo(5);
        assertThat(properties.autoIndex()).isTrue();
        assertThat(properties.reindexMode()).isEqualTo(ReindexMode.NONE);
    }

    @Test
    void aBatchSizeBelowOneStopsTheStartWithAClearMessage() {
        assertThatThrownBy(() -> new EmbeddingProperties(0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size must be at least 1");
    }

    @ParameterizedTest
    @CsvSource({"0", "51", "-3"})
    void aSearchLimitOutOfRangeIsRejected(int limit) {
        assertThatThrownBy(() -> new EmbeddingProperties(null, limit, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("search-limit must be between 1 and 50");
    }

    @ParameterizedTest
    @CsvSource({"false,NONE", "true,CHANGED_MODEL", "FORCE,ALL", "' True ',CHANGED_MODEL"})
    void readsTheReindexSetting(String setting, ReindexMode expected) {
        assertThat(new EmbeddingProperties(null, null, null, setting).reindexMode()).isEqualTo(expected);
    }

    @Test
    void anUnknownReindexSettingIsRejected() {
        assertThatThrownBy(() -> new EmbeddingProperties(null, null, null, "sometimes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("false, true or force");
    }
}
