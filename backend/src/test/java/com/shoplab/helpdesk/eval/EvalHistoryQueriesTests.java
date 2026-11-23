package com.shoplab.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The queries of {@code eval/queries/} run against a real PostgreSQL with three saved runs, so the documented way to
 * read the history cannot rot (specs/LAB-31, FR-016 and FR-017). They are written for {@code psql} (variables as
 * {@code :'name'}); the test replaces each by a literal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class EvalHistoryQueriesTests {

    private static final Path QUERIES = Path.of("../eval/queries");

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedThreeRuns() {
        HistoryQueryFixtures.seedThreeRuns(store);
    }

    @AfterEach
    void removeTestRows() {
        HistoryQueryFixtures.removeTestRows(jdbc);
    }

    private List<Map<String, Object>> run(String file, String... variables) throws IOException {
        return HistoryQueryFixtures.run(jdbc, file, variables);
    }

    private static List<Map<String, Object>> mine(List<Map<String, Object>> rows) {
        return rows.stream().filter(row -> String.valueOf(row.get("run_id")).startsWith("hist-")).toList();
    }

    private static double number(Object value) {
        return ((BigDecimal) value).doubleValue();
    }

    @Test
    void everyQueryOfTheFolderIsExercisedHere() throws IOException {
        try (var files = Files.list(QUERIES)) {
            assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql")).sorted()
                    .toList()).containsExactly("01-list-runs.sql", "02-run-parameters.sql", "03-run-summary.sql",
                    "04-metric-evolution.sql", "05-compare-runs.sql", "06-compare-parameters.sql",
                    "07-regressions.sql", "08-find-by-prompt.sql", "09-find-by-model.sql");
        }
    }

    @Test
    void theListShowsTheNewestRunFirstWithItsPromptsModelAndHeadlineMean() throws IOException {
        List<Map<String, Object>> rows = mine(run("01-list-runs.sql"));

        assertThat(rows).extracting(row -> row.get("run_id")).containsExactly("hist-c", "hist-b", "hist-a");
        assertThat(rows.get(1).get("assistant_model")).isEqualTo("qwen2.5:3b");
        assertThat(String.valueOf(rows.get(1).get("prompts"))).contains("chat-system=4")
                .contains("chat-empty-context=default");
        assertThat(number(rows.get(2).get("headline_mean"))).isEqualTo(0.75);
        assertThat(number(rows.get(1).get("headline_mean"))).isEqualTo(0.55);
        assertThat(number(rows.get(0).get("headline_mean"))).isEqualTo(0.5);
        assertThat(rows.get(0).get("prompts")).isNull();
    }

    @Test
    void theParametersOfARunAreReadInOnePlace() throws IOException {
        Map<String, Object> row = run("02-run-parameters.sql", "run_id", "hist-a").get(0);

        assertThat(row).containsEntry("kind", "QUALITY").containsEntry("embedding_model", "bge-m3")
                .containsEntry("assistant_model", "llama3.2:3b").containsEntry("cases_measured", 3);
        assertThat(String.valueOf(row.get("prompts"))).contains("chat-context");
        assertThat(String.valueOf(row.get("retrieval_config"))).contains("top_k");
        assertThat(String.valueOf(row.get("judge"))).contains("qwen2.5:7b");
    }

    @Test
    void theSummaryRecalculatedFromTheResultsIsTheOneOfTheRun() throws IOException {
        List<Map<String, Object>> rows = run("03-run-summary.sql", "run_id", "hist-a");

        Map<String, Object> faithfulness = rows.stream().filter(row -> "all".equals(row.get("scope"))
                && "faithfulness".equals(row.get("metric"))).findFirst().orElseThrow();
        assertThat(faithfulness).containsEntry("n", 2L).containsEntry("empty", 1L);
        assertThat(number(faithfulness.get("mean"))).isEqualTo(0.75);
        Map<String, Object> relevancy = rows.stream().filter(row -> "all".equals(row.get("scope"))
                && "answer_relevancy".equals(row.get("metric"))).findFirst().orElseThrow();
        assertThat(relevancy).containsEntry("n", 2L).containsEntry("empty", 0L);
        assertThat(number(relevancy.get("mean"))).isEqualTo(0.65);
        assertThat(rows).extracting(row -> row.get("scope")).contains("all", "payments");
        assertThat(rows.get(0).get("scope")).isEqualTo("all");
    }

    @Test
    void theEvolutionOfAMetricGoesRunByRunInOrderAndSkipsTheRunsWithoutIt() throws IOException {
        List<Map<String, Object>> rows = mine(run("04-metric-evolution.sql", "metric", "faithfulness"));

        assertThat(rows).extracting(row -> row.get("run_id")).containsExactly("hist-a", "hist-b");
        assertThat(rows.stream().map(row -> number(row.get("mean"))).toList()).containsExactly(0.75, 0.55);
        assertThat(mine(run("04-metric-evolution.sql", "metric", "reciprocal_rank")))
                .extracting(row -> row.get("run_id")).containsExactly("hist-c");
    }

    @Test
    void twoRunsAreComparedCaseByCaseAndARewrittenCaseIsNotComparable() throws IOException {
        List<Map<String, Object>> rows = run("05-compare-runs.sql", "run_a", "hist-a", "run_b", "hist-b",
                "metric", "faithfulness");

        assertThat(rows).extracting(row -> row.get("case_id")).containsExactly("G402", "G401", "G403");
        assertThat(number(rows.get(0).get("delta"))).isEqualTo(-0.3);
        assertThat(rows.get(0).get("comparable")).isEqualTo(true);
        assertThat(number(rows.get(1).get("delta"))).isEqualTo(-0.1);
        assertThat(rows.get(1).get("comparable")).isEqualTo(false);
        assertThat(rows.get(2).get("delta")).isNull();
        assertThat(rows.get(2).get("comparable")).isEqualTo(true);
    }

    @Test
    void aCaseThatIsOnlyInOneRunIsCompared() throws IOException {
        List<Map<String, Object>> rows = run("05-compare-runs.sql", "run_a", "hist-a", "run_b", "hist-c",
                "metric", "faithfulness");

        assertThat(rows).extracting(row -> row.get("case_id")).containsExactlyInAnyOrder("G401", "G402", "G403");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("value_b")).isNull());
    }

    @Test
    void theParametersThatDifferBetweenTwoRunsAreListed() throws IOException {
        assertThat(run("06-compare-parameters.sql", "run_a", "hist-a", "run_b", "hist-b"))
                .extracting(row -> row.get("parameter")).containsExactly("assistant_model", "prompts");
        assertThat(run("06-compare-parameters.sql", "run_a", "hist-a", "run_b", "hist-c"))
                .extracting(row -> row.get("parameter"))
                .contains("kind", "judge", "evaluator", "prompts", "retrieval_config")
                .doesNotContain("embedding_model", "dataset_hash", "commit_sha", "origin");
        assertThat(run("06-compare-parameters.sql", "run_a", "hist-a", "run_b", "hist-a")).isEmpty();
    }

    @Test
    void theRegressionsAreTheComparableCasesThatDroppedMoreThanTheThreshold() throws IOException {
        List<Map<String, Object>> rows = run("07-regressions.sql", "run_a", "hist-a", "run_b", "hist-b",
                "metric", "faithfulness", "drop", "0.05");

        assertThat(rows).extracting(row -> row.get("case_id")).containsExactly("G402");
        assertThat(number(rows.get(0).get("delta"))).isEqualTo(-0.3);
        assertThat(run("07-regressions.sql", "run_a", "hist-a", "run_b", "hist-b", "metric", "faithfulness",
                "drop", "0.5")).isEmpty();
    }

    @Test
    void aRunIsFoundByThePromptAndTheVersionItUsed() throws IOException {
        assertThat(mine(run("08-find-by-prompt.sql", "prompt_name", "chat-system", "prompt_version", "4")))
                .extracting(row -> row.get("run_id")).containsExactly("hist-b");
        assertThat(mine(run("08-find-by-prompt.sql", "prompt_name", "chat-system", "prompt_version", "3")))
                .extracting(row -> row.get("run_id")).containsExactly("hist-a");
    }

    @Test
    void aRunIsFoundByTheModelOfTheAssistant() throws IOException {
        assertThat(mine(run("09-find-by-model.sql", "model", "qwen2.5:3b"))).extracting(row -> row.get("run_id"))
                .containsExactly("hist-b");
        assertThat(mine(run("09-find-by-model.sql", "model", "llama3.2:3b"))).extracting(row -> row.get("run_id"))
                .containsExactly("hist-c", "hist-a");
    }
}
