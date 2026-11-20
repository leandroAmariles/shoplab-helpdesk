package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.constants.SmallTalkConstants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SmallTalkPropertiesTests {

    @Test
    void usesTheDocumentedDefaults() {
        SmallTalkProperties properties = new SmallTalkProperties(null, null, null, null, null, null, null);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.topics()).isEqualTo(SmallTalkConstants.DEFAULT_TOPICS);
    }

    @Test
    void blankTextsFallBackToTheDefaults() {
        SmallTalkProperties properties = new SmallTalkProperties(false, "", null, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.topics()).isEqualTo(SmallTalkConstants.DEFAULT_TOPICS);
    }

    @Test
    void theTopicsAreTrimmed() {
        assertThat(new SmallTalkProperties(true, "  pagos  ", null, null, null, null, null).topics())
                .isEqualTo("pagos");
    }

    // --- the lexicon ---

    @Test
    void withoutConfigurationEveryListIsTheBuiltInOne() {
        SmallTalkProperties properties = new SmallTalkProperties(null, null, null, null, null, null, null);

        assertThat(properties.greetings()).containsExactlyInAnyOrderElementsOf(SmallTalkConstants.GREETINGS);
        assertThat(properties.thanks()).containsExactlyInAnyOrderElementsOf(SmallTalkConstants.THANKS);
        assertThat(properties.farewells()).containsExactlyInAnyOrderElementsOf(SmallTalkConstants.FAREWELLS);
        assertThat(properties.acknowledgments()).containsExactlyInAnyOrderElementsOf(SmallTalkConstants.ACKNOWLEDGMENTS);
        assertThat(properties.capabilities()).containsExactlyInAnyOrderElementsOf(SmallTalkConstants.CAPABILITIES);
    }

    @Test
    void aConfiguredListReplacesTheBuiltInOneAndBlankEntriesAreDropped() {
        SmallTalkProperties properties = new SmallTalkProperties(null, null, List.of(" hola ", "", "  ", "hey"),
                List.of("", " "), null, null, null);

        assertThat(properties.greetings()).containsExactly("hola", "hey");
        assertThat(properties.thanks()).as("only blanks keep the default")
                .containsExactlyInAnyOrderElementsOf(SmallTalkConstants.THANKS);
    }

    @Test
    void theListsBindFromCommaSeparatedValuesLikeAnEnvironmentVariable() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "shoplab.chat.small-talk.greetings", "hola,buenas tardes, hey",
                "shoplab.chat.small-talk.farewells", "",
                "shoplab.chat.small-talk.enabled", "false")));

        SmallTalkProperties properties = binder.bind("shoplab.chat.small-talk", SmallTalkProperties.class).get();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.greetings()).containsExactly("hola", "buenas tardes", "hey");
        assertThat(properties.farewells()).as("an empty variable keeps the default")
                .containsExactlyInAnyOrderElementsOf(SmallTalkConstants.FAREWELLS);
    }
}
