package com.shoplab.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shoplab.helpdesk.eval.application.port.out.EvalHistoryPort;
import com.shoplab.helpdesk.eval.application.port.out.EvalRunStorePort;
import com.shoplab.helpdesk.eval.domain.RunComparator;
import com.shoplab.helpdesk.eval.domain.model.CaseComparison;
import com.shoplab.helpdesk.eval.domain.model.MetricComparison;
import com.shoplab.helpdesk.eval.domain.model.ParameterDifference;
import com.shoplab.helpdesk.eval.domain.model.RunComparison;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseComparisonStatus;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The comparison of the backend gives the same answer as the documented queries of {@code eval/queries/} on the same
 * saved runs (specs/LAB-32, FR-019 and SC-005): what the screen shows and what a person finds with {@code psql} cannot
 * disagree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class EvalHistoryEquivalenceTests {

    @Autowired
    private EvalRunStorePort store;

    @Autowired
    private EvalHistoryPort history;

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

    private StoredRun stored(String runId) {
        return history.find(runId).block().orElseThrow();
    }

    private RunComparison compare(String base, String target, String metric, String drop) {
        return RunComparator.compare(stored(base), stored(target), metric, new BigDecimal(drop));
    }

    private static double number(Object value) {
        return ((BigDecimal) value).doubleValue();
    }

    @ParameterizedTest
    @CsvSource({"faithfulness,0.05", "faithfulness,0.5", "faithfulness,0", "answer_relevancy,0.05",
            "answer_relevancy,0", "context_precision,0.05", "context_precision,0"})
    void theCasesThatGotWorseAreExactlyTheRowsOfTheRegressionsQuery(String metric, String drop) throws IOException {
        List<Map<String, Object>> rows = HistoryQueryFixtures.run(jdbc, "07-regressions.sql", "run_a", "hist-a",
                "run_b", "hist-b", "metric", metric, "drop", drop);

        List<CaseComparison> worse = compare("hist-a", "hist-b", metric, drop).cases().stream()
                .filter(c -> c.status() == CaseComparisonStatus.WORSE).toList();

        assertThat(worse).extracting(CaseComparison::caseId)
                .containsExactlyElementsOf(rows.stream().map(row -> (String) row.get("case_id")).toList());
        assertThat(worse).extracting(CaseComparison::baseValue)
                .containsExactlyElementsOf(rows.stream().map(row -> number(row.get("value_a"))).toList());
        assertThat(worse).extracting(CaseComparison::targetValue)
                .containsExactlyElementsOf(rows.stream().map(row -> number(row.get("value_b"))).toList());
    }

    @Test
    void theValuesOfEachCaseAreTheOnesOfTheCompareRunsQueryAndARewrittenCaseIsNotComparable() throws IOException {
        List<Map<String, Object>> rows = HistoryQueryFixtures.run(jdbc, "05-compare-runs.sql", "run_a", "hist-a",
                "run_b", "hist-b", "metric", "faithfulness");

        RunComparison comparison = compare("hist-a", "hist-b", "faithfulness", "0.05");

        assertThat(comparison.cases()).extracting(CaseComparison::caseId)
                .containsExactlyInAnyOrderElementsOf(rows.stream().map(row -> (String) row.get("case_id")).toList());
        for (Map<String, Object> row : rows) {
            CaseComparison compared = comparison.cases().stream()
                    .filter(c -> c.caseId().equals(row.get("case_id"))).findFirst().orElseThrow();
            if (Boolean.FALSE.equals(row.get("comparable"))) {
                assertThat(compared.status()).isEqualTo(CaseComparisonStatus.NOT_COMPARABLE);
            } else if (row.get("delta") == null) {
                assertThat(compared.status()).isEqualTo(CaseComparisonStatus.NO_VALUE);
            } else {
                assertThat(compared.baseValue()).isEqualTo(number(row.get("value_a")));
                assertThat(compared.targetValue()).isEqualTo(number(row.get("value_b")));
                assertThat(compared.delta()).isCloseTo(number(row.get("delta")), within(1e-12));
            }
        }
        assertThat(comparison.cases().stream().filter(c -> c.status() == CaseComparisonStatus.NOT_COMPARABLE))
                .hasSize((int) rows.stream().filter(row -> Boolean.FALSE.equals(row.get("comparable"))).count());
    }

    @Test
    void theMeansOfTheMetricsAreTheOnesOfTheRunSummaryQuery() throws IOException {
        RunComparison comparison = compare("hist-a", "hist-b", "faithfulness", "0.05");

        for (String run : List.of("hist-a", "hist-b")) {
            List<Map<String, Object>> rows = HistoryQueryFixtures.run(jdbc, "03-run-summary.sql", "run_id", run)
                    .stream().filter(row -> "all".equals(row.get("scope"))).toList();
            for (Map<String, Object> row : rows) {
                MetricComparison metric = comparison.metrics().stream()
                        .filter(m -> m.metric().equals(row.get("metric"))).findFirst().orElseThrow();
                var stat = "hist-a".equals(run) ? metric.base() : metric.target();
                assertThat(stat.n()).isEqualTo(((Long) row.get("n")).intValue());
                if (row.get("mean") == null) {
                    assertThat(stat.mean()).isNull();
                } else {
                    assertThat(stat.mean()).isCloseTo(number(row.get("mean")), within(1e-9));
                }
            }
        }
    }

    private static Set<String> section(String field) {
        return Set.of(field.contains(".") ? field.substring(0, field.indexOf('.')) : field);
    }

    @Test
    void theParametersThatDifferAreTheOnesOfTheCompareParametersQueryAndOnlyTheRightOnesWarn() throws IOException {
        for (String[] pair : new String[][] {{"hist-a", "hist-b"}, {"hist-b", "hist-a"}}) {
            Set<String> expected = HistoryQueryFixtures.run(jdbc, "06-compare-parameters.sql", "run_a", pair[0],
                    "run_b", pair[1]).stream().map(row -> (String) row.get("parameter")).collect(Collectors.toSet());

            RunComparison comparison = compare(pair[0], pair[1], "faithfulness", "0.05");

            assertThat(comparison.parameters().stream().map(ParameterDifference::field)
                    .flatMap(field -> section(field).stream()).collect(Collectors.toSet())).isEqualTo(expected);
            Set<String> warning = expected.stream().filter(name -> !Set.of("prompts", "commit_sha", "origin", "kind")
                    .contains(name)).collect(Collectors.toSet());
            assertThat(comparison.warnings().stream().flatMap(field -> section(field).stream())
                    .collect(Collectors.toSet())).isEqualTo(warning);
        }
    }
}
