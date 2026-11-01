package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RagPropertiesTests {

    @Test
    void usesTheDocumentedDefaults() {
        RagProperties properties = new RagProperties(null, null, null, null, null, null, null);

        assertThat(properties.topK()).isEqualTo(4);
        assertThat(properties.similarityThreshold()).isEqualTo(0.6);
        assertThat(properties.maxContextCharacters()).isEqualTo(8000);
        assertThat(properties.contextTemplate()).isEqualTo(ChatConstants.DEFAULT_CONTEXT_TEMPLATE);
        assertThat(properties.emptyContextTemplate()).isEqualTo(ChatConstants.DEFAULT_EMPTY_CONTEXT_TEMPLATE);
        assertThat(properties.noContextMode()).isEqualTo(NoContextMode.FIXED_MESSAGE);
        assertThat(properties.noContextMessage()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
    }

    @Test
    void aBlankNoContextMessageFallsBackToTheDefaultAndTheModeCanBeChosen() {
        RagProperties properties = new RagProperties(null, null, null, null, null, NoContextMode.MODEL, "  ");

        assertThat(properties.noContextMode()).isEqualTo(NoContextMode.MODEL);
        assertThat(properties.noContextMessage()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
    }

    @Test
    void blankTemplatesFallBackToTheDefaults() {
        RagProperties properties = new RagProperties(null, null, null, " ", "", null, null);

        assertThat(properties.contextTemplate()).isEqualTo(ChatConstants.DEFAULT_CONTEXT_TEMPLATE);
        assertThat(properties.emptyContextTemplate()).isEqualTo(ChatConstants.DEFAULT_EMPTY_CONTEXT_TEMPLATE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 20})
    void acceptsATopKFromOneToTwenty(int topK) {
        assertThat(new RagProperties(topK, null, null, null, null, null, null).topK()).isEqualTo(topK);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, 100})
    void rejectsATopKOutsideOneToTwentyNamingThePropertyAndTheRange(int topK) {
        assertThatThrownBy(() -> new RagProperties(topK, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.top-k")
                .hasMessageContaining("between 1 and 20")
                .hasMessageContaining("was " + topK);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void acceptsAThresholdFromZeroToOne(double threshold) {
        RagProperties properties = new RagProperties(null, threshold, null, null, null, null, null);

        assertThat(properties.similarityThreshold()).isEqualTo(threshold);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, 5.0})
    void rejectsAThresholdOutsideZeroToOne(double threshold) {
        assertThatThrownBy(() -> new RagProperties(null, threshold, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.similarity-threshold")
                .hasMessageContaining("between 0.0 and 1.0");
    }

    @Test
    void rejectsABudgetUnderFiveHundredCharacters() {
        assertThatThrownBy(() -> new RagProperties(null, null, 499, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.max-context-characters")
                .hasMessageContaining("at least 500");
        assertThat(new RagProperties(null, null, 500, null, null, null, null).maxContextCharacters()).isEqualTo(500);
    }

    @Test
    void theContextTemplateNeedsBothMarkers() {
        assertThatThrownBy(() -> new RagProperties(null, null, null, "only {query}", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.context-template");
        assertThatThrownBy(() -> new RagProperties(null, null, null, "only {context}", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.context-template");
    }

    @Test
    void aContextTemplateWithOtherBracedNamesFailsAtStartUpNamingTheProperty() {
        assertThatThrownBy(() -> new RagProperties(null, null, null, "{query} {context} {unknown}", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.context-template cannot be rendered");
    }

    @Test
    void aContextTemplateWithTheTwoMarkersAndPlainTextIsAccepted() {
        RagProperties properties = new RagProperties(null, null, null, "Q: {query}\nC: {context}", null, null, null);

        assertThat(properties.contextTemplate()).isEqualTo("Q: {query}\nC: {context}");
    }

    @Test
    void theEmptyContextTemplateNeedsTheQueryMarker() {
        assertThatThrownBy(() -> new RagProperties(null, null, null, null, "no marker here", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.rag.empty-context-template");
    }
}
