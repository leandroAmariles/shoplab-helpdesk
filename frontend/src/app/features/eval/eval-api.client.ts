import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import {
  EvaluatorInfo,
  JudgeInfo,
  MetricStat,
  OriginCount,
  RetrievalConfig,
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

interface WireMetric {
  mean: number | null;
  n: number;
}

interface WirePrompt {
  name: string;
  version: number | null;
  source: string;
  pinned: boolean;
}

interface WireRunItem {
  run_id: string;
  kind: RunKind;
  started_at: string;
  finished_at: string;
  origin: string;
  commit_sha: string | null;
  assistant_model: string | null;
  prompts: WirePrompt[];
  cases_measured: number;
  metrics: Record<string, WireMetric>;
}

interface WireListing {
  kind: RunKind;
  shown: number;
  total: number;
  total_all_origins: number;
  origins: OriginCount[];
  runs: WireRunItem[];
}

interface WireJudge {
  model: string;
  underlying_model: string | null;
  temperature: number | null;
  seed: number | null;
  max_input_chars: number | null;
}

interface WireEvaluator {
  library: string;
  library_version: string | null;
  judgment_instructions_version: string | null;
}

interface WireRetrieval {
  top_k: number;
  similarity_threshold: number;
  max_context_characters: number;
  depth: number | null;
  ks: number[] | null;
}

interface WireRunHeader {
  run_id: string;
  kind: RunKind;
  started_at: string;
  finished_at: string;
  recorded_at: string;
  origin: string;
  commit_sha: string | null;
  dataset_hash: string;
  cases_measured: number;
  cases_excluded: number;
  assistant_model: string | null;
  embedding_model: string;
  prompts: WirePrompt[];
  judge: WireJudge | null;
  evaluator: WireEvaluator | null;
  retrieval_config: WireRetrieval;
}

interface WireResult {
  case_id: string;
  revision: string;
  category: string;
  question: string;
  reference_answer: string;
  expected_documents: string[];
  case_kind: 'answered' | 'unanswered';
  scores: Record<string, number | null>;
  retrieved_documents: string[];
  abstained: boolean | null;
  trace_id: string | null;
  trace_url: string | null;
}

interface WireDetail {
  run: WireRunHeader;
  summary: { scope: string; cases: number; metrics: Record<string, WireMetric> }[];
  results: WireResult[];
}

export function toMetric(wire: WireMetric): MetricStat {
  return { mean: wire.mean ?? null, n: wire.n };
}

export function toMetrics(wire: Record<string, WireMetric>): Record<string, MetricStat> {
  return Object.fromEntries(Object.entries(wire).map(([name, stat]) => [name, toMetric(stat)]));
}

export function toPrompts(wire: WirePrompt[]): RunPrompt[] {
  return wire.map((prompt) => ({
    name: prompt.name,
    version: prompt.version ?? null,
    source: prompt.source,
    pinned: prompt.pinned,
  }));
}

function toRunItem(wire: WireRunItem): RunListItem {
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

function toListing(wire: WireListing): RunListing {
  return {
    kind: wire.kind,
    shown: wire.shown,
    total: wire.total,
    totalAllOrigins: wire.total_all_origins,
    origins: wire.origins.map((origin) => ({ origin: origin.origin, count: origin.count })),
    runs: wire.runs.map(toRunItem),
  };
}

function toJudge(wire: WireJudge | null): JudgeInfo | null {
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

function toEvaluator(wire: WireEvaluator | null): EvaluatorInfo | null {
  return wire === null
    ? null
    : {
        library: wire.library,
        libraryVersion: wire.library_version ?? null,
        judgmentInstructionsVersion: wire.judgment_instructions_version ?? null,
      };
}

function toRetrieval(wire: WireRetrieval): RetrievalConfig {
  return {
    topK: wire.top_k,
    similarityThreshold: wire.similarity_threshold,
    maxContextCharacters: wire.max_context_characters,
    depth: wire.depth ?? null,
    ks: wire.ks ?? null,
  };
}

function toRunHeader(wire: WireRunHeader): RunHeader {
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

function toResult(wire: WireResult): StoredResult {
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

function toSummary(wire: WireDetail['summary'][number]): ScopeSummary {
  return { scope: wire.scope, cases: wire.cases, metrics: toMetrics(wire.metrics) };
}

function toDetail(wire: WireDetail): RunDetail {
  return {
    run: toRunHeader(wire.run),
    summary: wire.summary.map(toSummary),
    results: wire.results.map(toResult),
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
      .get<WireListing>(`${EVAL_HISTORY_URL}/runs`, { params, context: ownErrors() })
      .pipe(map(toListing));
  }

  /** One run whole: how it was measured, its summary and the result of every case. */
  getRun(runId: string): Observable<RunDetail> {
    return this.http
      .get<WireDetail>(`${EVAL_HISTORY_URL}/runs/${encodeURIComponent(runId)}`, {
        context: ownErrors(),
      })
      .pipe(map(toDetail));
  }
}
