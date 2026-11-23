package com.shoplab.helpdesk.eval.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.eval.application.usecase.TraceUrlBuilder;
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
import com.shoplab.helpdesk.eval.domain.model.RunListItem;
import com.shoplab.helpdesk.eval.domain.model.RunListing;
import com.shoplab.helpdesk.eval.domain.model.ScopeSummary;
import com.shoplab.helpdesk.eval.domain.model.StoredResult;
import com.shoplab.helpdesk.eval.domain.model.StoredRun;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RunComparisonResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RunDetailResponse;
import com.shoplab.helpdesk.eval.infrastructure.in.rest.dto.RunListResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns what the history read into the answers of the read endpoints, field by field. */
public final class EvalHistoryMapper {

    private EvalHistoryMapper() {
    }

    public static RunListResponse toResponse(RunListing listing) {
        return new RunListResponse(listing.kind().name(), listing.runs().size(), listing.total(),
                listing.totalAllOrigins(),
                listing.origins().stream().map(o -> new RunListResponse.OriginPart(o.origin(), o.count())).toList(),
                listing.runs().stream().map(EvalHistoryMapper::toPart).toList());
    }

    public static RunDetailResponse toResponse(StoredRun run, TraceUrlBuilder trace) {
        RunDetailResponse.RunPart header = new RunDetailResponse.RunPart(run.runId(), run.kind().name(),
                run.startedAt(), run.finishedAt(), run.recordedAt(), run.origin(), run.commitSha(),
                run.datasetHash(), run.casesMeasured(), run.casesExcluded(), run.assistantModel(),
                run.embeddingModel(), prompts(run.prompts()), judge(run.judge()), evaluator(run.evaluator()),
                retrieval(run.retrievalConfig()));
        return new RunDetailResponse(header, run.summary().stream().map(EvalHistoryMapper::scope).toList(),
                run.results().stream().map(result -> result(result, trace)).toList());
    }

    public static RunComparisonResponse toResponse(RunComparison comparison) {
        return new RunComparisonResponse(header(comparison.base()), header(comparison.target()),
                comparison.metric(), comparison.drop().doubleValue(),
                comparison.metrics().stream().map(EvalHistoryMapper::metric).toList(),
                comparison.parameters().stream().map(EvalHistoryMapper::parameter).toList(), comparison.warnings(),
                counts(comparison.counts()), comparison.cases().stream().map(EvalHistoryMapper::comparedCase).toList());
    }

    private static RunComparisonResponse.RunPart header(RunHeader header) {
        return new RunComparisonResponse.RunPart(header.runId(), header.kind().name(), header.startedAt(),
                header.origin());
    }

    private static RunComparisonResponse.MetricPart metric(MetricComparison metric) {
        return new RunComparisonResponse.MetricPart(metric.metric(), stat(metric.base()), stat(metric.target()),
                metric.delta());
    }

    private static RunComparisonResponse.StatPart stat(MetricStat stat) {
        return new RunComparisonResponse.StatPart(stat.mean(), stat.n());
    }

    private static RunComparisonResponse.ParameterPart parameter(ParameterDifference difference) {
        return new RunComparisonResponse.ParameterPart(difference.field(), difference.base(), difference.target(),
                difference.affectsComparability());
    }

    private static RunComparisonResponse.CountsPart counts(ComparisonCounts counts) {
        return new RunComparisonResponse.CountsPart(counts.worse(), counts.better(), counts.same(),
                counts.noValue(), counts.notComparable(), counts.onlyBase(), counts.onlyTarget());
    }

    private static RunComparisonResponse.CasePart comparedCase(CaseComparison comparison) {
        return new RunComparisonResponse.CasePart(comparison.caseId(), comparison.category(), comparison.question(),
                comparison.status().name(), comparison.baseValue(), comparison.targetValue(), comparison.delta());
    }

    private static RunListResponse.RunPart toPart(RunListItem item) {
        return new RunListResponse.RunPart(item.runId(), item.kind().name(), item.startedAt(), item.finishedAt(),
                item.origin(), item.commitSha(), item.assistantModel(), prompts(item.prompts()),
                item.casesMeasured(), metrics(item.metrics()));
    }

    private static List<RunListResponse.PromptPart> prompts(List<PromptVersion> prompts) {
        return prompts.stream().map(prompt -> new RunListResponse.PromptPart(prompt.name(), prompt.version(),
                prompt.source().name(), Boolean.TRUE.equals(prompt.pinned()))).toList();
    }

    private static Map<String, RunListResponse.MetricPart> metrics(Map<String, MetricStat> metrics) {
        Map<String, RunListResponse.MetricPart> parts = new LinkedHashMap<>();
        metrics.forEach((name, stat) -> parts.put(name, new RunListResponse.MetricPart(stat.mean(), stat.n())));
        return parts;
    }

    private static RunDetailResponse.JudgePart judge(JudgeInfo judge) {
        return judge == null ? null : new RunDetailResponse.JudgePart(judge.model(), judge.underlyingModel(),
                judge.temperature(), judge.seed(), judge.maxInputChars());
    }

    private static RunDetailResponse.EvaluatorPart evaluator(EvaluatorInfo evaluator) {
        return evaluator == null ? null : new RunDetailResponse.EvaluatorPart(evaluator.library(),
                evaluator.libraryVersion(), evaluator.judgmentInstructionsVersion());
    }

    private static RunDetailResponse.RetrievalPart retrieval(RetrievalConfig config) {
        return new RunDetailResponse.RetrievalPart(config.topK(), config.similarityThreshold(),
                config.maxContextCharacters(), config.depth(), config.ks());
    }

    private static RunDetailResponse.ScopePart scope(ScopeSummary summary) {
        return new RunDetailResponse.ScopePart(summary.scope(), summary.cases(), metrics(summary.metrics()));
    }

    private static RunDetailResponse.ResultPart result(StoredResult result, TraceUrlBuilder trace) {
        String traceUrl = result.traceId() == null ? null : trace.urlFor(result.traceId()).orElse(null);
        return new RunDetailResponse.ResultPart(result.caseId(), result.revision(), result.category(),
                result.question(), result.referenceAnswer(), result.expectedDocuments(), result.caseKind().value(),
                result.scores(), result.retrievedDocuments(), result.abstained(), result.traceId(), traceUrl);
    }
}
