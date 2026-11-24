import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  ComparisonCasePart,
  ComparisonMetricPart,
  ComparisonParameterPart,
  MetricStatResponse,
  RunComparisonResponse,
  RunDetailEvaluatorPart,
  RunDetailHeaderResponse,
  RunDetailJudgePart,
  RunDetailResponse,
  RunDetailResultPart,
  RunDetailRetrievalPart,
  RunDetailScopePart,
  RunListItemResponse,
  RunListResponse,
  RunPromptResponse,
  ComparedRunPart,
} from '@leandroamariles/shoplab-contracts';
import { map, Observable } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import {
  ComparedRun,
  EvaluatorInfo,
  JudgeInfo,
  MetricStat,
  RetrievalConfig,
  RunComparison,
  RunDetail,
  RunHeader,
  RunKind,
  RunListing,
  RunListItem,
  RunPrompt,
  ScopeSummary,
  StoredResult,
} from './eval-history';

export const EVAL_HISTORY_URL = '/api/eval/history';

/** The screen shows its own errors (cause in Spanish, retry), so the generic snack bar stays quiet. */
function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

export function toMetric(wire: MetricStatResponse): MetricStat {
  return { mean: wire.mean ?? null, n: wire.n };
}

export function toMetrics(wire: Record<string, MetricStatResponse>): Record<string, MetricStat> {
  return Object.fromEntries(Object.entries(wire).map(([name, stat]) => [name, toMetric(stat)]));
}

export function toPrompts(wire: RunPromptResponse[]): RunPrompt[] {
  return wire.map((prompt) => ({
    name: prompt.name,
    version: prompt.version ?? null,
    source: prompt.source,
    pinned: prompt.pinned,
  }));
}

function toRunItem(wire: RunListItemResponse): RunListItem {
  return {
    runId: wire.run_id,
    kind: wire.kind,
    startedAt: wire.started_at,
    finishedAt: wire.finished_at,
    origin: wire.origin,
    commitSha: wire.commit_sha ?? null,
    assistantModel: wire.assistant_model ?? null,
    prompts: toPrompts(wire.prompts),
    casesMeasured: wire.cases_measured,
    metrics: toMetrics(wire.metrics),
  };
}

function toListing(wire: RunListResponse): RunListing {
  return {
    kind: wire.kind,
    shown: wire.shown,
    total: wire.total,
    totalAllOrigins: wire.total_all_origins,
    origins: wire.origins.map((origin) => ({ origin: origin.origin, count: origin.count })),
    runs: wire.runs.map(toRunItem),
  };
}

function toJudge(wire: RunDetailJudgePart | null): JudgeInfo | null {
  return wire === null
    ? null
    : {
        model: wire.model,
        underlyingModel: wire.underlying_model ?? null,
        temperature: wire.temperature ?? null,
        seed: wire.seed ?? null,
        maxInputChars: wire.max_input_chars ?? null,
      };
}

function toEvaluator(wire: RunDetailEvaluatorPart | null): EvaluatorInfo | null {
  return wire === null
    ? null
    : {
        library: wire.library,
        libraryVersion: wire.library_version ?? null,
        judgmentInstructionsVersion: wire.judgment_instructions_version ?? null,
      };
}

function toRetrieval(wire: RunDetailRetrievalPart): RetrievalConfig {
  return {
    topK: wire.top_k ?? 0,
    similarityThreshold: wire.similarity_threshold ?? 0,
    maxContextCharacters: wire.max_context_characters ?? 0,
    depth: wire.depth ?? null,
    ks: wire.ks ?? null,
  };
}

function toRunHeader(wire: RunDetailHeaderResponse): RunHeader {
  return {
    runId: wire.run_id,
    kind: wire.kind,
    startedAt: wire.started_at,
    finishedAt: wire.finished_at,
    recordedAt: wire.recorded_at,
    origin: wire.origin,
    commitSha: wire.commit_sha ?? null,
    datasetHash: wire.dataset_hash,
    casesMeasured: wire.cases_measured,
    casesExcluded: wire.cases_excluded,
    assistantModel: wire.assistant_model ?? null,
    embeddingModel: wire.embedding_model,
    prompts: toPrompts(wire.prompts),
    judge: toJudge(wire.judge ?? null),
    evaluator: toEvaluator(wire.evaluator ?? null),
    retrievalConfig: toRetrieval(wire.retrieval_config),
  };
}

function toResult(wire: RunDetailResultPart): StoredResult {
  return {
    caseId: wire.case_id,
    revision: wire.revision,
    category: wire.category,
    question: wire.question,
    referenceAnswer: wire.reference_answer,
    expectedDocuments: wire.expected_documents,
    caseKind: wire.case_kind,
    scores: Object.fromEntries(
      Object.entries(wire.scores).map(([metric, value]) => [metric, value ?? null]),
    ),
    retrievedDocuments: wire.retrieved_documents,
    abstained: wire.abstained ?? null,
    traceId: wire.trace_id ?? null,
    traceUrl: wire.trace_url ?? null,
  };
}

function toSummary(wire: RunDetailScopePart): ScopeSummary {
  return { scope: wire.scope, cases: wire.cases, metrics: toMetrics(wire.metrics) };
}

function toDetail(wire: RunDetailResponse): RunDetail {
  return {
    run: toRunHeader(wire.run),
    summary: wire.summary.map(toSummary),
    results: wire.results.map(toResult),
  };
}

function toComparedRun(wire: ComparedRunPart): ComparedRun {
  return { runId: wire.run_id, kind: wire.kind, startedAt: wire.started_at, origin: wire.origin };
}

function toComparisonMetric(metric: ComparisonMetricPart) {
  return {
    metric: metric.metric,
    base: toMetric(metric.base),
    target: toMetric(metric.target),
    delta: metric.delta ?? null,
  };
}

function toComparisonParameter(parameter: ComparisonParameterPart) {
  return {
    field: parameter.field,
    base: parameter.base ?? null,
    target: parameter.target ?? null,
    affectsComparability: parameter.affects_comparability,
  };
}

function toComparisonCase(item: ComparisonCasePart) {
  return {
    caseId: item.case_id,
    category: item.category,
    question: item.question,
    status: item.status,
    baseValue: item.base_value ?? null,
    targetValue: item.target_value ?? null,
    delta: item.delta ?? null,
  };
}

function toComparison(wire: RunComparisonResponse): RunComparison {
  return {
    base: toComparedRun(wire.base),
    target: toComparedRun(wire.target),
    metric: wire.metric,
    drop: wire.drop,
    metrics: wire.metrics.map(toComparisonMetric),
    parameters: wire.parameters.map(toComparisonParameter),
    warnings: wire.warnings,
    counts: {
      worse: wire.counts.worse,
      better: wire.counts.better,
      same: wire.counts.same,
      noValue: wire.counts.no_value,
      notComparable: wire.counts.not_comparable,
      onlyBase: wire.counts.only_base,
      onlyTarget: wire.counts.only_target,
    },
    cases: wire.cases.map(toComparisonCase),
  };
}

/** Reads the history of evaluation runs of the backend (read only; it needs no token). */
@Injectable({ providedIn: 'root' })
export class EvalApiClient {
  private readonly http = inject(HttpClient);

  /** The most recent `limit` runs of a kind, oldest first; `origin` keeps only the runs of that origin. */
  listRuns(kind: RunKind, origin: string | null, limit: number): Observable<RunListing> {
    let params = new HttpParams().set('kind', kind).set('limit', limit);
    if (origin) {
      params = params.set('origin', origin);
    }
    return this.http
      .get<RunListResponse>(`${EVAL_HISTORY_URL}/runs`, { params, context: ownErrors() })
      .pipe(map(toListing));
  }

  /** One run whole: how it was measured, its summary and the result of every case. */
  getRun(runId: string): Observable<RunDetail> {
    return this.http
      .get<RunDetailResponse>(`${EVAL_HISTORY_URL}/runs/${encodeURIComponent(runId)}`, {
        context: ownErrors(),
      })
      .pipe(map(toDetail));
  }

  /** Two runs of the same kind for one metric; a case is worse or better when it moves more than `drop`. */
  compare(base: string, target: string, metric: string, drop: number): Observable<RunComparison> {
    const params = new HttpParams()
      .set('base', base)
      .set('target', target)
      .set('metric', metric)
      .set('drop', drop);
    return this.http
      .get<RunComparisonResponse>(`${EVAL_HISTORY_URL}/compare`, { params, context: ownErrors() })
      .pipe(map(toComparison));
  }
}
