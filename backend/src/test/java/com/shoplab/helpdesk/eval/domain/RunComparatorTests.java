package com.shoplab.helpdesk.eval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.domain.model.CaseComparison;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.ParameterDifference;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.RunComparison;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseComparisonStatus;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseKind;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.eval.domain.model.enums.RunKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The comparison of two runs: the rule of eval/queries/07-regressions.sql, in exact arithmetic (specs/LAB-32 R9). */
class RunComparatorTests {

    private static final String METRIC = "faithfulness";
    private static final BigDecimal DROP = new BigDecimal("0.05");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static StoredResult result(String caseId, String revision, Double value) {
        Map<String, Double> scores = new HashMap<>();
        scores.put(METRIC, value);
        return new StoredResult(caseId, revision, "returns", "Pregunta de " + caseId, "Respuesta de referencia",
                List.of("politica-devoluciones"), CaseKind.ANSWERED, scores, List.of(), null, null);
    }

    private static StoredRun run(String runId, List<StoredResult> results) {
        return runWith(runId, "dev", "chat", "abc1234", HASH_A, results, RunKind.QUALITY);
    }

    private static StoredRun runWith(String runId, String origin, String assistantModel, String commit,
            String datasetHash, List<StoredResult> results, RunKind kind) {
        Instant at = Instant.parse("2026-11-21T14:02:11Z");
        Map<String, MetricStat> all = new LinkedHashMap<>();
        all.put(METRIC, new MetricStat(0.7, results.size()));
        boolean quality = kind == RunKind.QUALITY;
        return new StoredRun(runId, kind, at, at.plusSeconds(60), at.plusSeconds(61), origin, commit, datasetHash,
                results.size(), 0, assistantModel, "bge-m3",
                List.of(new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false)),
                quality ? new JudgeInfo("judge", "qwen", 0.0, 7, 8000) : null,
                quality ? new EvaluatorInfo("ragas", "0.4.4", "1") : null,
                new RetrievalConfig(4, 0.6, 8000, null, null),
                List.of(new ScopeSummary("all", results.size(), all)), results);
    }

    private static CaseComparison only(RunComparison comparison, String caseId) {
        return comparison.cases().stream().filter(c -> c.caseId().equals(caseId)).findFirst().orElseThrow();
    }

    static Stream<Arguments> thresholdCases() {
        return Stream.of(
                // 0.80 - 0.75 is 0.05000000000000004 in double: in exact arithmetic it is not more than 0.05
                Arguments.of(0.80, 0.75, "0.05", CaseComparisonStatus.SAME, -0.05),
                Arguments.of(0.80, 0.7499, "0.05", CaseComparisonStatus.WORSE, -0.0501),
                Arguments.of(0.75, 0.80, "0.05", CaseComparisonStatus.SAME, 0.05),
                Arguments.of(0.70, 0.80, "0.05", CaseComparisonStatus.BETTER, 0.1),
                Arguments.of(0.80, 0.80, "0", CaseComparisonStatus.SAME, 0.0),
                Arguments.of(0.80, 0.79, "0", CaseComparisonStatus.WORSE, -0.01),
                Arguments.of(0.20, 1.00, "1", CaseComparisonStatus.SAME, 0.8),
                Arguments.of(1.00, 0.00, "0.5", CaseComparisonStatus.WORSE, -1.0));
    }

    @ParameterizedTest
    @MethodSource("thresholdCases")
    void aChangeIsWorseOrBetterOnlyWhenItIsStrictlyMoreThanTheTolerance(double base, double target, String drop,
            CaseComparisonStatus status, double delta) {
        RunComparison comparison = RunComparator.compare(run("a", List.of(result("G001", HASH_A, base))),
                run("b", List.of(result("G001", HASH_A, target))), METRIC, new BigDecimal(drop));

        CaseComparison only = only(comparison, "G001");
        assertThat(only.status()).isEqualTo(status);
        assertThat(only.baseValue()).isEqualTo(base);
        assertThat(only.targetValue()).isEqualTo(target);
        assertThat(only.delta()).isEqualTo(delta);
    }

    @Test
    void aCaseWithoutValueInEitherRunSaysNothing() {
        RunComparison comparison = RunComparator.compare(
                run("a", List.of(result("G001", HASH_A, null), result("G002", HASH_A, 0.9))),
                run("b", List.of(result("G001", HASH_A, 0.5), result("G002", HASH_A, null))), METRIC, DROP);

        for (String id : List.of("G001", "G002")) {
            CaseComparison only = only(comparison, id);
            assertThat(only.status()).isEqualTo(CaseComparisonStatus.NO_VALUE);
            assertThat(only.delta()).isNull();
        }
    }

    @Test
    void aCaseThatChangedBetweenTheRunsIsNotComparableAndItsValuesAreNotClaimed() {
        RunComparison comparison = RunComparator.compare(run("a", List.of(result("G001", HASH_A, 0.9))),
                run("b", List.of(result("G001", HASH_B, 0.1))), METRIC, DROP);

        CaseComparison only = only(comparison, "G001");
        assertThat(only.status()).isEqualTo(CaseComparisonStatus.NOT_COMPARABLE);
        assertThat(only.baseValue()).isNull();
        assertThat(only.targetValue()).isNull();
        assertThat(only.delta()).isNull();
    }

    @Test
    void aCaseInOnlyOneRunIsSaidSoAndTheQuestionComesFromThatRun() {
        RunComparison comparison = RunComparator.compare(run("a", List.of(result("G001", HASH_A, 0.9))),
                run("b", List.of(result("G002", HASH_A, 0.9))), METRIC, DROP);

        assertThat(only(comparison, "G001").status()).isEqualTo(CaseComparisonStatus.ONLY_BASE);
        assertThat(only(comparison, "G001").question()).isEqualTo("Pregunta de G001");
        assertThat(only(comparison, "G002").status()).isEqualTo(CaseComparisonStatus.ONLY_TARGET);
        assertThat(only(comparison, "G002").delta()).isNull();
    }

    @Test
    void theQuestionOfACaseInBothRunsIsTheOneOfTheTarget() {
        StoredResult edited = new StoredResult("G001", HASH_B, "shipping", "Pregunta nueva", "Respuesta", List.of(),
                CaseKind.ANSWERED, Map.of(METRIC, 0.5), List.of(), null, null);

        RunComparison comparison = RunComparator.compare(run("a", List.of(result("G001", HASH_A, 0.9))),
                run("b", List.of(edited)), METRIC, DROP);

        assertThat(only(comparison, "G001").question()).isEqualTo("Pregunta nueva");
        assertThat(only(comparison, "G001").category()).isEqualTo("shipping");
    }

    @Test
    void theCasesAreOrderedByDeltaWorstFirstThenThoseWithoutDeltaAndTiesByCaseId() {
        RunComparison comparison = RunComparator.compare(
                run("a", List.of(result("G003", HASH_A, 0.9), result("G001", HASH_A, 0.9),
                        result("G002", HASH_A, 0.9), result("G005", HASH_A, null), result("G004", HASH_A, 0.5),
                        result("G006", HASH_A, 0.5))),
                run("b", List.of(result("G003", HASH_A, 0.5), result("G001", HASH_A, 0.5),
                        result("G002", HASH_A, 0.1), result("G005", HASH_A, 0.5), result("G004", HASH_A, 0.9),
                        result("G007", HASH_A, 0.5))), METRIC, DROP);

        assertThat(comparison.cases()).extracting(CaseComparison::caseId)
                .containsExactly("G002", "G001", "G003", "G004", "G005", "G006", "G007");
    }

    @Test
    void theCountsAddUpToTheUnionOfTheCases() {
        RunComparison comparison = RunComparator.compare(
                run("a", List.of(result("G001", HASH_A, 0.9), result("G002", HASH_A, 0.5),
                        result("G003", HASH_A, 0.5), result("G004", HASH_A, null), result("G005", HASH_A, 0.5),
                        result("G006", HASH_A, 0.5))),
                run("b", List.of(result("G001", HASH_A, 0.1), result("G002", HASH_A, 0.9),
                        result("G003", HASH_A, 0.5), result("G004", HASH_A, 0.5), result("G005", HASH_B, 0.5),
                        result("G007", HASH_A, 0.5))), METRIC, DROP);

        assertThat(comparison.counts()).satisfies(counts -> {
            assertThat(counts.worse()).isEqualTo(1);
            assertThat(counts.better()).isEqualTo(1);
            assertThat(counts.same()).isEqualTo(1);
            assertThat(counts.noValue()).isEqualTo(1);
            assertThat(counts.notComparable()).isEqualTo(1);
            assertThat(counts.onlyBase()).isEqualTo(1);
            assertThat(counts.onlyTarget()).isEqualTo(1);
        });
        assertThat(comparison.cases()).hasSize(7);
    }

    @Test
    void aMetricThatTheResultDoesNotHaveIsAGap() {
        StoredResult noKey = new StoredResult("G001", HASH_A, "returns", "Pregunta", "Respuesta", List.of(),
                CaseKind.ANSWERED, Map.of("answer_relevancy", 0.5), List.of(), null, null);

        RunComparison comparison = RunComparator.compare(run("a", List.of(noKey)),
                run("b", List.of(result("G001", HASH_A, 0.5))), METRIC, DROP);

        assertThat(only(comparison, "G001").status()).isEqualTo(CaseComparisonStatus.NO_VALUE);
    }

    @Test
    void theParametersThatDifferAreListedAndOnlyTheOnesThatChangeWhatIsMeasuredWarn() {
        StoredRun base = run("a", List.of());
        StoredRun target = new StoredRun("b", RunKind.QUALITY, base.startedAt(), base.finishedAt(),
                base.recordedAt(), "ci", "def5678", HASH_B, 0, 0, "chat-2", "bge-m3",
                List.of(new PromptVersion("chat-system", 4, PromptSource.LANGFUSE, false)),
                new JudgeInfo("judge", "qwen", 0.2, 7, 8000), new EvaluatorInfo("ragas", "0.4.4", "1"),
                new RetrievalConfig(4, 0.6, 8000, null, null), base.summary(), List.of());

        RunComparison comparison = RunComparator.compare(base, target, METRIC, DROP);

        assertThat(comparison.parameters()).extracting(ParameterDifference::field, ParameterDifference::base,
                ParameterDifference::target, ParameterDifference::affectsComparability).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("prompts.chat-system.version", "3", "4", false),
                        org.assertj.core.groups.Tuple.tuple("assistant_model", "chat", "chat-2", true),
                        org.assertj.core.groups.Tuple.tuple("judge.temperature", "0.0", "0.2", true),
                        org.assertj.core.groups.Tuple.tuple("dataset_hash", HASH_A, HASH_B, true),
                        org.assertj.core.groups.Tuple.tuple("commit_sha", "abc1234", "def5678", false),
                        org.assertj.core.groups.Tuple.tuple("origin", "dev", "ci", false));
        assertThat(comparison.warnings()).containsExactlyInAnyOrder("assistant_model", "judge.temperature",
                "dataset_hash");
    }

    @Test
    void twoRunsMeasuredTheSameWayHaveNoDifferencesAndNoWarnings() {
        RunComparison comparison = RunComparator.compare(run("a", List.of()), run("b", List.of()), METRIC, DROP);

        assertThat(comparison.parameters()).isEmpty();
        assertThat(comparison.warnings()).isEmpty();
    }

    @Test
    void anUnknownValueAgainstAKnownOneIsADifference() {
        StoredRun base = run("a", List.of());
        StoredRun target = runWith("b", "dev", null, null, HASH_A, List.of(), RunKind.QUALITY);

        RunComparison comparison = RunComparator.compare(base, target, METRIC, DROP);

        assertThat(comparison.parameters()).extracting(ParameterDifference::field, ParameterDifference::base,
                ParameterDifference::target).contains(
                        org.assertj.core.groups.Tuple.tuple("assistant_model", "chat", null),
                        org.assertj.core.groups.Tuple.tuple("commit_sha", "abc1234", null));
    }

    @Test
    void aPromptOfOnlyOneRunIsADifferenceInItsVersion() {
        StoredRun base = run("a", List.of());
        StoredRun target = new StoredRun("b", RunKind.QUALITY, base.startedAt(), base.finishedAt(),
                base.recordedAt(), "dev", "abc1234", HASH_A, 0, 0, "chat", "bge-m3",
                List.of(new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                        new PromptVersion("chat-empty-context", null, PromptSource.DEFAULT, false)),
                base.judge(), base.evaluator(), base.retrievalConfig(), base.summary(), List.of());

        RunComparison comparison = RunComparator.compare(base, target, METRIC, DROP);

        assertThat(comparison.parameters()).extracting(ParameterDifference::field, ParameterDifference::base,
                ParameterDifference::target).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("prompts.chat-empty-context.version", null, "default"));
    }

    @Test
    void theMetricsListTheUnionOfTheMetricsOfBothRunsWithTheDifferenceOfTheMeans() {
        Map<String, MetricStat> baseAll = new LinkedHashMap<>();
        baseAll.put("faithfulness", new MetricStat(0.7134, 96));
        baseAll.put("context_precision", new MetricStat(null, 0));
        Map<String, MetricStat> targetAll = new LinkedHashMap<>();
        targetAll.put("faithfulness", new MetricStat(0.68, 96));
        targetAll.put("answer_relevancy", new MetricStat(0.8, 90));
        StoredRun base = run("a", List.of());
        StoredRun target = run("b", List.of());
        base = new StoredRun(base.runId(), base.kind(), base.startedAt(), base.finishedAt(), base.recordedAt(),
                base.origin(), base.commitSha(), base.datasetHash(), 96, 0, base.assistantModel(),
                base.embeddingModel(), base.prompts(), base.judge(), base.evaluator(), base.retrievalConfig(),
                List.of(new ScopeSummary("all", 96, baseAll)), List.of());
        target = new StoredRun(target.runId(), target.kind(), target.startedAt(), target.finishedAt(),
                target.recordedAt(), target.origin(), target.commitSha(), target.datasetHash(), 96, 0,
                target.assistantModel(), target.embeddingModel(), target.prompts(), target.judge(),
                target.evaluator(), target.retrievalConfig(), List.of(new ScopeSummary("all", 96, targetAll)),
                List.of());

        RunComparison comparison = RunComparator.compare(base, target, "faithfulness", DROP);

        assertThat(comparison.metrics()).extracting(m -> m.metric()).containsExactlyInAnyOrder("faithfulness",
                "context_precision", "answer_relevancy");
        assertThat(comparison.metrics().stream().filter(m -> m.metric().equals("faithfulness")).findFirst()
                .orElseThrow().delta()).isEqualTo(-0.0334);
        assertThat(comparison.metrics().stream().filter(m -> m.metric().equals("context_precision")).findFirst()
                .orElseThrow().delta()).isNull();
        assertThat(comparison.metrics().stream().filter(m -> m.metric().equals("answer_relevancy")).findFirst()
                .orElseThrow().base()).isEqualTo(new MetricStat(null, 0));
        assertThat(RunComparator.metricNames(base, target)).containsExactlyInAnyOrder("faithfulness",
                "context_precision", "answer_relevancy");
    }

    @Test
    void theHeadersAndTheToleranceAreKept() {
        RunComparison comparison = RunComparator.compare(run("a", List.of()), run("b", List.of()), METRIC,
                new BigDecimal("0.1"));

        assertThat(comparison.base().runId()).isEqualTo("a");
        assertThat(comparison.target().runId()).isEqualTo("b");
        assertThat(comparison.metric()).isEqualTo(METRIC);
        assertThat(comparison.drop()).isEqualByComparingTo("0.1");
    }
}
