package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.classifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.config.SmallTalkProperties;
import com.shoplab.helpdesk.chat.domain.model.constants.SmallTalkConstants;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SmallTalkClassifierTests {

    private final SmallTalkClassifier classifier = classifier();

    /** At least 20 courtesies in Spanish and English (SC-001); every one of them must be recognized (SC-002). */
    @ParameterizedTest
    @ValueSource(strings = {
            "hola", "Hola", "buenos días", "¡Buenas tardes!", "buenas noches", "hey", "hello", "good morning",
            "¿cómo estás?", "how are you",
            "gracias", "Muchas gracias", "mil gracias", "thanks", "thank you", "thanks a lot",
            "adiós", "chao", "hasta luego", "nos vemos", "bye", "goodbye",
            "ok", "vale", "perfecto", "genial", "entendido", "muy bien",
            "¿qué puedes hacer?", "¿en qué me puedes ayudar?", "¿quién eres?", "what can you do", "who are you"})
    void recognizesTheCourtesiesOfTheLexicon(String message) {
        assertThat(classifier.isSmallTalk(message)).as(message).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOLAAA!!", "hola 😊", "Gracias!!", "ÁDIOS", "ADIÓS", "  hola   ", "hola\n", "¡¡HOLA!!",
            "holaaaaa", "muuuuchas gracias", "HELLOOO", "thaaanks", "okkkk", "hola...", "¿¿qué puedes hacer??",
            "hola 👋🏽", "Buenos días!!!"})
    void toleratesCaseAccentsSignsEmojisAndRepeatedLetters(String message) {
        assertThat(classifier.isSmallTalk(message)).as(message).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"muchas gracias, hasta luego", "hola, ¿qué puedes hacer?", "vale, perfecto",
            "hola gracias", "buenas tardes, gracias", "hello, thanks", "hola, buenos días, ¿cómo estás?",
            "gracias, adiós", "ok, gracias, hasta luego"})
    void recognizesSeveralCourtesiesInOneMessage(String message) {
        assertThat(classifier.isSmallTalk(message)).as(message).isTrue();
    }

    @Test
    void theSameMessageAlwaysGivesTheSameClass() {
        for (int i = 0; i < 20; i++) {
            assertThat(classifier.isSmallTalk("¡Hola, buenas tardes!")).isTrue();
            assertThat(classifier.isSmallTalk("¿Cuánto tarda un envío?")).isFalse();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void nullAndBlankMessagesAreNotCourtesies(String message) {
        assertThat(classifier.isSmallTalk(message)).isFalse();
    }

    @Test
    void normalizesToLowerCasePlainWordsWithSingleSpaces() {
        assertThat(SmallTalkClassifier.normalize("  ¡HOLAAA!!   Qué   TAL? 😊 ")).isEqualTo("hola que tal");
        assertThat(SmallTalkClassifier.normalize("Adiós, señor")).isEqualTo("adios senor");
    }

    @Test
    void theSegmentationCapIsTheLongestPhraseOfTheBuiltInLexicon() {
        int longest = Stream.of(SmallTalkConstants.GREETINGS, SmallTalkConstants.THANKS, SmallTalkConstants.FAREWELLS,
                        SmallTalkConstants.ACKNOWLEDGMENTS, SmallTalkConstants.CAPABILITIES)
                .flatMap(Set::stream)
                .mapToInt(phrase -> phrase.split(" ").length)
                .max().orElseThrow();

        assertThat(classifier.maxPhraseTokens()).isEqualTo(longest).isEqualTo(5);
    }

    // --- a lexicon taken from the configuration (shoplab.chat.small-talk.*) ---

    private static SmallTalkClassifier withLexicon(List<String> greetings, List<String> thanks) {
        return new SmallTalkClassifier(new SmallTalkProperties(null, null, null, greetings, thanks, List.of("adios"),
                List.of("vale"), List.of("quien eres")));
    }

    @Test
    void aConfiguredListReplacesTheBuiltInOneAndTheOthersKeepTheirs() {
        SmallTalkClassifier custom = new SmallTalkClassifier(new SmallTalkProperties(null, null, null,
                List.of("hola mundo", "que onda"), null, null, null, null));

        assertThat(custom.isSmallTalk("hola mundo")).isTrue();
        assertThat(custom.isSmallTalk("¿Qué onda?")).isTrue();
        assertThat(custom.isSmallTalk("hola")).as("replaced greetings").isFalse();
        assertThat(custom.isSmallTalk("gracias")).as("thanks keep the built-in list").isTrue();
        assertThat(custom.isSmallTalk("¿qué puedes hacer?")).as("capabilities keep the built-in list").isTrue();
    }

    @Test
    void configuredPhrasesAreNormalizedLikeTheMessages() {
        SmallTalkClassifier custom = withLexicon(List.of("  ¡QUÉ TAL, Amigo!  "), List.of("Gracias Totales"));

        assertThat(custom.isSmallTalk("que tal amigo")).isTrue();
        assertThat(custom.isSmallTalk("GRACIAS totales!!")).isTrue();
    }

    @Test
    void theSegmentationCapFollowsTheConfiguredLexicon() {
        SmallTalkClassifier shortLexicon = withLexicon(List.of("hola"), List.of("gracias"));
        SmallTalkClassifier longLexicon = withLexicon(List.of("muy buenas tardes tengan ustedes"), List.of("gracias"));

        assertThat(shortLexicon.maxPhraseTokens()).isEqualTo(2);
        assertThat(longLexicon.maxPhraseTokens()).isEqualTo(5);
        assertThat(longLexicon.isSmallTalk("muy buenas tardes tengan ustedes")).isTrue();
        assertThat(shortLexicon.isSmallTalk("muy buenas tardes tengan ustedes")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hola 2", "1234", "😊", "!!!", "uno dos tres cuatro cinco seis siete ocho nueve diez once"})
    void aConfiguredPhraseWithADigitNothingOrTooManyWordsFailsNamingTheProperty(String phrase) {
        assertThatThrownBy(() -> withLexicon(List.of(phrase), List.of("gracias")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shoplab.chat.small-talk.greetings")
                .hasMessageContaining(phrase);
    }

    @Test
    void aBadPhraseInAnotherListNamesThatList() {
        assertThatThrownBy(() -> withLexicon(List.of("hola"), List.of("gracias 5")))
                .hasMessageContaining("shoplab.chat.small-talk.thanks");
    }

    @Test
    void everyPhraseOfTheLexiconIsAlreadyNormalized() {
        Stream.of(SmallTalkConstants.GREETINGS, SmallTalkConstants.THANKS, SmallTalkConstants.FAREWELLS,
                        SmallTalkConstants.ACKNOWLEDGMENTS, SmallTalkConstants.CAPABILITIES)
                .flatMap(Set::stream)
                .forEach(phrase -> {
                    assertThat(SmallTalkClassifier.normalize(phrase)).as(phrase).isEqualTo(phrase);
                    assertThat(classifier.isSmallTalk(phrase)).as(phrase).isTrue();
                });
    }
}
