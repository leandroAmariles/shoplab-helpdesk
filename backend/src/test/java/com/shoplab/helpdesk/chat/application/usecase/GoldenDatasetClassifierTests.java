package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.classifier;
import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.support.InjectionMessages;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The classifier and the golden dataset (eval/golden.jsonl, LAB-27 and LAB-114) cannot drift apart: if the lexicon or
 * the dataset changes and they contradict each other, this test fails. The questions of the knowledge cases and of the
 * injection cases are never courtesies; the small-talk cases always are.
 */
class GoldenDatasetClassifierTests {

    /** Maven runs the tests with the module as working directory. */
    private static final Path GOLDEN = Path.of("..", "eval", "golden.jsonl");
    private static final Set<String> COURTESY_OPENERS = Set.of("hola", "buenas", "buenos", "gracias");

    private record GoldenCase(String id, String question, String category, boolean hasDocuments) {
    }

    private static List<GoldenCase> cases;

    private final SmallTalkClassifier classifier = classifier();

    @BeforeAll
    static void readTheDataset() throws IOException {
        assertThat(GOLDEN).as("the golden dataset the test cross-checks against").exists();
        JsonMapper mapper = JsonMapper.builder().build();
        cases = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    JsonNode node = mapper.readTree(line);
                    return new GoldenCase(node.get("id").asString(), node.get("question").asString(),
                            node.get("category").asString(), !node.get("expected_documents").isEmpty());
                })
                .toList();
    }

    @Test
    void theDatasetHasTheConversationCasesOfLab114() {
        assertThat(cases).hasSize(120);
        assertThat(cases).filteredOn(golden -> golden.category().equals("small_talk")).hasSize(12);
        assertThat(cases).filteredOn(golden -> golden.category().equals("injection")).hasSize(12);
    }

    @Test
    void noKnowledgeQuestionIsACourtesy() {
        List<String> falsePositives = cases.stream()
                .filter(golden -> !golden.category().equals("small_talk") && !golden.category().equals("injection"))
                .filter(golden -> classifier.isSmallTalk(golden.question()))
                .map(GoldenCase::id)
                .toList();

        assertThat(falsePositives).as("knowledge questions classified as small talk").isEmpty();
    }

    @Test
    void noInjectionIsACourtesy() {
        List<String> falsePositives = cases.stream()
                .filter(golden -> golden.category().equals("injection"))
                .filter(golden -> classifier.isSmallTalk(golden.question()))
                .map(GoldenCase::id)
                .toList();

        assertThat(falsePositives).as("injections classified as small talk").isEmpty();
    }

    @Test
    void everySmallTalkCaseIsACourtesy() {
        List<String> missed = cases.stream()
                .filter(golden -> golden.category().equals("small_talk"))
                .filter(golden -> !classifier.isSmallTalk(golden.question()))
                .map(GoldenCase::id)
                .toList();

        assertThat(missed).as("small-talk cases the lexicon does not recognize").isEmpty();
    }

    @Test
    void theMixedCasesStartWithACourtesyAndAreStillQuestions() {
        List<GoldenCase> mixed = cases.stream()
                .filter(GoldenCase::hasDocuments)
                .filter(golden -> COURTESY_OPENERS.contains(firstWord(golden.question())))
                .toList();

        assertThat(mixed).hasSizeGreaterThanOrEqualTo(5);
        assertThat(mixed).noneMatch(golden -> classifier.isSmallTalk(golden.question()));
    }

    @Test
    void theInjectionsOfTheDatasetAreTheOnesOfThePromptInjectionTests() {
        List<String> inDataset = cases.stream().filter(golden -> golden.category().equals("injection"))
                .map(GoldenCase::question).toList();

        assertThat(inDataset).containsExactlyElementsOf(InjectionMessages.INJECTIONS);
    }

    private static String firstWord(String question) {
        return question.trim().split("[\\s,.!¡¿?]+")[0].toLowerCase(java.util.Locale.ROOT);
    }
}
