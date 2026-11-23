package com.shoplab.helpdesk.eval.domain;

import com.shoplab.helpdesk.eval.domain.model.CaseComparison;
import com.shoplab.helpdesk.eval.domain.model.ComparisonCounts;
import com.shoplab.helpdesk.eval.domain.model.EvaluatorInfo;
import com.shoplab.helpdesk.eval.domain.model.JudgeInfo;
import com.shoplab.helpdesk.eval.domain.model.MetricComparison;
import com.shoplab.helpdesk.eval.domain.model.MetricStat;
import com.shoplab.helpdesk.eval.domain.model.ParameterDifference;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.RetrievalConfig;
import com.shoplab.helpdesk.eval.domain.model.RunComparison;
import com.shoplab.helpdesk.eval.domain.model.RunHeader;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.domain.model.enums.CaseComparisonStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares two saved runs of the same kind for one metric, with the rule of {@code eval/queries/07-regressions.sql}:
 * a case is worse when the base value minus the target value is strictly more than the tolerance, in exact decimal
 * arithmetic (0.80 to 0.75 is not worse than 0.05: in a {@code double} it would be). Pure logic, no Spring: the use
 * case reads the runs and checks the request (specs/LAB-32, research R9).
 */
public final class RunComparator {

    private static final String PROMPT_DEFAULT = "default";
    private static final String ALL_SCOPE = "all";

    private RunComparator() {
    }

    /** The metrics that either run has for the whole set, sorted by name. */
    public static List<String> metricNames(StoredRun base, StoredRun target) {
        TreeSet<String> names = new TreeSet<>(wholeSet(base).keySet());
        names.addAll(wholeSet(target).keySet());
        return List.copyOf(names);
    }

    public static RunComparison compare(StoredRun base, StoredRun target, String metric, BigDecimal drop) {
        List<ParameterDifference> parameters = parameters(base, target);
        List<CaseComparison> cases = cases(base, target, metric, drop);
        return new RunComparison(header(base), header(target), metric, drop, metrics(base, target), parameters,
                parameters.stream().filter(ParameterDifference::affectsComparability)
                        .map(ParameterDifference::field).toList(),
                counts(cases), cases);
    }

    private static RunHeader header(StoredRun run) {
        return new RunHeader(run.runId(), run.kind(), run.startedAt(), run.origin());
    }

    private static Map<String, MetricStat> wholeSet(StoredRun run) {
        return run.summary().stream().filter(scope -> ALL_SCOPE.equals(scope.scope())).findFirst()
                .map(ScopeSummary::metrics).orElse(Map.of());
    }

    private static List<MetricComparison> metrics(StoredRun base, StoredRun target) {
        Map<String, MetricStat> baseStats = wholeSet(base);
        Map<String, MetricStat> targetStats = wholeSet(target);
        return metricNames(base, target).stream().map(name -> {
            MetricStat baseStat = baseStats.getOrDefault(name, new MetricStat(null, 0));
            MetricStat targetStat = targetStats.getOrDefault(name, new MetricStat(null, 0));
            return new MetricComparison(name, baseStat, targetStat, delta(baseStat.mean(), targetStat.mean()));
        }).toList();
    }

    private static Double delta(Double base, Double target) {
        if (base == null || target == null) {
            return null;
        }
        return BigDecimal.valueOf(target).subtract(BigDecimal.valueOf(base)).doubleValue();
    }

    private static List<CaseComparison> cases(StoredRun base, StoredRun target, String metric, BigDecimal drop) {
        Map<String, StoredResult> baseResults = byCase(base);
        Map<String, StoredResult> targetResults = byCase(target);
        TreeSet<String> ids = new TreeSet<>(baseResults.keySet());
        ids.addAll(targetResults.keySet());
        List<CaseComparison> cases = new ArrayList<>();
        for (String id : ids) {
            cases.add(compareCase(baseResults.get(id), targetResults.get(id), metric, drop));
        }
        cases.sort(Comparator.comparing(CaseComparison::delta, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CaseComparison::caseId));
        return cases;
    }

    private static Map<String, StoredResult> byCase(StoredRun run) {
        return run.results().stream().collect(Collectors.toMap(StoredResult::caseId, Function.identity(),
                (first, second) -> first, TreeMap::new));
    }

    private static CaseComparison compareCase(StoredResult base, StoredResult target, String metric,
            BigDecimal drop) {
        if (base == null) {
            return without(target, CaseComparisonStatus.ONLY_TARGET);
        }
        if (target == null) {
            return without(base, CaseComparisonStatus.ONLY_BASE);
        }
        if (!base.revision().equals(target.revision())) {
            return without(target, CaseComparisonStatus.NOT_COMPARABLE);
        }
        Double baseValue = base.scores().get(metric);
        Double targetValue = target.scores().get(metric);
        if (baseValue == null || targetValue == null) {
            return without(target, CaseComparisonStatus.NO_VALUE);
        }
        BigDecimal baseExact = BigDecimal.valueOf(baseValue);
        BigDecimal targetExact = BigDecimal.valueOf(targetValue);
        CaseComparisonStatus status = CaseComparisonStatus.SAME;
        if (baseExact.subtract(targetExact).compareTo(drop) > 0) {
            status = CaseComparisonStatus.WORSE;
        } else if (targetExact.subtract(baseExact).compareTo(drop) > 0) {
            status = CaseComparisonStatus.BETTER;
        }
        return new CaseComparison(target.caseId(), target.category(), target.question(), status, baseValue,
                targetValue, targetExact.subtract(baseExact).doubleValue());
    }

    /** A case about which no value is claimed. */
    private static CaseComparison without(StoredResult result, CaseComparisonStatus status) {
        return new CaseComparison(result.caseId(), result.category(), result.question(), status, null, null, null);
    }

    private static ComparisonCounts counts(List<CaseComparison> cases) {
        Map<CaseComparisonStatus, Integer> counts = new EnumMap<>(CaseComparisonStatus.class);
        cases.forEach(comparison -> counts.merge(comparison.status(), 1, Integer::sum));
        return new ComparisonCounts(count(counts, CaseComparisonStatus.WORSE),
                count(counts, CaseComparisonStatus.BETTER), count(counts, CaseComparisonStatus.SAME),
                count(counts, CaseComparisonStatus.NO_VALUE), count(counts, CaseComparisonStatus.NOT_COMPARABLE),
                count(counts, CaseComparisonStatus.ONLY_BASE), count(counts, CaseComparisonStatus.ONLY_TARGET));
    }

    private static int count(Map<CaseComparisonStatus, Integer> counts, CaseComparisonStatus status) {
        return counts.getOrDefault(status, 0);
    }

    /**
     * Every parameter that is not the same. Judge, evaluator, models, retrieval and dataset change what is measured;
     * the prompts, the commit and the origin are what is normally being tested.
     */
    private static List<ParameterDifference> parameters(StoredRun base, StoredRun target) {
        Map<String, String[]> pairs = new LinkedHashMap<>();
        promptVersions(base, target, pairs);
        pairs.put("assistant_model", pair(base.assistantModel(), target.assistantModel()));
        pairs.put("embedding_model", pair(base.embeddingModel(), target.embeddingModel()));
        judge(base.judge(), target.judge(), pairs);
        evaluator(base.evaluator(), target.evaluator(), pairs);
        retrieval(base.retrievalConfig(), target.retrievalConfig(), pairs);
        pairs.put("dataset_hash", pair(base.datasetHash(), target.datasetHash()));
        pairs.put("commit_sha", pair(base.commitSha(), target.commitSha()));
        pairs.put("origin", pair(base.origin(), target.origin()));
        List<ParameterDifference> differences = new ArrayList<>();
        pairs.forEach((field, values) -> {
            if (!Objects.equals(values[0], values[1])) {
                differences.add(new ParameterDifference(field, values[0], values[1], affectsComparability(field)));
            }
        });
        return differences;
    }

    private static boolean affectsComparability(String field) {
        return !(field.startsWith("prompts.") || "commit_sha".equals(field) || "origin".equals(field));
    }

    private static String[] pair(Object base, Object target) {
        return new String[] {text(base), text(target)};
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void promptVersions(StoredRun base, StoredRun target, Map<String, String[]> pairs) {
        Map<String, String> baseVersions = promptVersions(base);
        Map<String, String> targetVersions = promptVersions(target);
        TreeSet<String> names = new TreeSet<>(baseVersions.keySet());
        names.addAll(targetVersions.keySet());
        for (String name : names) {
            pairs.put("prompts." + name + ".version", pair(baseVersions.get(name), targetVersions.get(name)));
        }
    }

    private static Map<String, String> promptVersions(StoredRun run) {
        Map<String, String> versions = new TreeMap<>();
        for (PromptVersion prompt : run.prompts()) {
            versions.put(prompt.name(), prompt.version() == null ? PROMPT_DEFAULT : prompt.version().toString());
        }
        return versions;
    }

    private static void judge(JudgeInfo base, JudgeInfo target, Map<String, String[]> pairs) {
        put(pairs, "judge.model", base, target, JudgeInfo::model);
        put(pairs, "judge.underlying_model", base, target, JudgeInfo::underlyingModel);
        put(pairs, "judge.temperature", base, target, JudgeInfo::temperature);
        put(pairs, "judge.seed", base, target, JudgeInfo::seed);
        put(pairs, "judge.max_input_chars", base, target, JudgeInfo::maxInputChars);
    }

    private static void evaluator(EvaluatorInfo base, EvaluatorInfo target, Map<String, String[]> pairs) {
        put(pairs, "evaluator.library", base, target, EvaluatorInfo::library);
        put(pairs, "evaluator.library_version", base, target, EvaluatorInfo::libraryVersion);
        put(pairs, "evaluator.judgment_instructions_version", base, target,
                EvaluatorInfo::judgmentInstructionsVersion);
    }

    private static void retrieval(RetrievalConfig base, RetrievalConfig target, Map<String, String[]> pairs) {
        put(pairs, "retrieval_config.top_k", base, target, RetrievalConfig::topK);
        put(pairs, "retrieval_config.similarity_threshold", base, target, RetrievalConfig::similarityThreshold);
        put(pairs, "retrieval_config.max_context_characters", base, target,
                RetrievalConfig::maxContextCharacters);
        put(pairs, "retrieval_config.depth", base, target, RetrievalConfig::depth);
        put(pairs, "retrieval_config.ks", base, target, RetrievalConfig::ks);
    }

    /** One part of a section of the run on both sides; a section a run does not have (no judge) reads as unknown. */
    private static <T> void put(Map<String, String[]> pairs, String field, T base, T target,
            Function<T, Object> part) {
        pairs.put(field, pair(base == null ? null : part.apply(base), target == null ? null : part.apply(target)));
    }
}
