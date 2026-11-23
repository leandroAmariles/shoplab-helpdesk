/**
 * Types of the read API of the evaluation history (specs/LAB-32, contracts/eval-history-api.md), in camelCase: the
 * API client translates the `snake_case` of the wire. A value without value is `null`, never 0.
 */
export type RunKind = 'QUALITY' | 'RETRIEVAL';

/** The value of a metric in a scope: the mean of the cases that have a value, and how many they are. */
export interface MetricStat {
  /** `null` when no case has a value (a gap, not a zero). */
  mean: number | null;
  n: number;
}

export interface RunPrompt {
  name: string;
  /** `null` when the text is the packaged default. */
  version: number | null;
  source: string;
  pinned: boolean;
}

export interface RunListItem {
  runId: string;
  kind: RunKind;
  startedAt: string;
  finishedAt: string;
  origin: string;
  commitSha: string | null;
  assistantModel: string | null;
  prompts: RunPrompt[];
  casesMeasured: number;
  /** The metrics the run has; one it does not have is not in the map. */
  metrics: Record<string, MetricStat>;
}

export interface OriginCount {
  origin: string;
  count: number;
}

export interface RunListing {
  kind: RunKind;
  shown: number;
  total: number;
  totalAllOrigins: number;
  origins: OriginCount[];
  /** Oldest first. */
  runs: RunListItem[];
}

export interface JudgeInfo {
  model: string;
  underlyingModel: string | null;
  temperature: number | null;
  seed: number | null;
  maxInputChars: number | null;
}

export interface EvaluatorInfo {
  library: string;
  libraryVersion: string | null;
  judgmentInstructionsVersion: string | null;
}

export interface RetrievalConfig {
  topK: number;
  similarityThreshold: number;
  maxContextCharacters: number;
  depth: number | null;
  ks: number[] | null;
}

export interface RunHeader {
  runId: string;
  kind: RunKind;
  startedAt: string;
  finishedAt: string;
  recordedAt: string;
  origin: string;
  commitSha: string | null;
  datasetHash: string;
  casesMeasured: number;
  casesExcluded: number;
  assistantModel: string | null;
  embeddingModel: string;
  prompts: RunPrompt[];
  judge: JudgeInfo | null;
  evaluator: EvaluatorInfo | null;
  retrievalConfig: RetrievalConfig;
}

export interface ScopeSummary {
  /** `all` or a category. */
  scope: string;
  cases: number;
  metrics: Record<string, MetricStat>;
}

export interface StoredResult {
  caseId: string;
  revision: string;
  category: string;
  question: string;
  referenceAnswer: string;
  expectedDocuments: string[];
  caseKind: 'answered' | 'unanswered';
  /** `null` = the metric does not apply or cannot be computed by definition; `0` is a real score. */
  scores: Record<string, number | null>;
  retrievedDocuments: string[];
  abstained: boolean | null;
  traceId: string | null;
  traceUrl: string | null;
}

export interface RunDetail {
  run: RunHeader;
  summary: ScopeSummary[];
  results: StoredResult[];
}

export type ComparisonStatus =
  'WORSE' | 'BETTER' | 'SAME' | 'NO_VALUE' | 'NOT_COMPARABLE' | 'ONLY_BASE' | 'ONLY_TARGET';

export interface ComparedRun {
  runId: string;
  kind: RunKind;
  startedAt: string;
  origin: string;
}

export interface MetricComparison {
  metric: string;
  base: MetricStat | null;
  target: MetricStat | null;
  delta: number | null;
}

export interface ParameterDifference {
  field: string;
  base: string | null;
  target: string | null;
  affectsComparability: boolean;
}

export interface CaseComparison {
  caseId: string;
  category: string;
  question: string;
  status: ComparisonStatus;
  baseValue: number | null;
  targetValue: number | null;
  delta: number | null;
}

export interface ComparisonCounts {
  worse: number;
  better: number;
  same: number;
  noValue: number;
  notComparable: number;
  onlyBase: number;
  onlyTarget: number;
}

export interface RunComparison {
  base: ComparedRun;
  target: ComparedRun;
  metric: string;
  drop: number;
  metrics: MetricComparison[];
  parameters: ParameterDifference[];
  warnings: string[];
  counts: ComparisonCounts;
  cases: CaseComparison[];
}

/** Whether a run can be compared with the reference, and if not, why. */
export type CompareAvailability = 'available' | 'is-reference' | 'no-reference' | 'other-kind';

export const DEFAULT_KIND: RunKind = 'QUALITY';
export const DEFAULT_LIMIT = 100;
export const LIMIT_STEPS: readonly number[] = [100, 250, 500];
export const MAX_SELECTED_METRICS = 5;
/** What a case has to score below, in quality, to be shown as failing until the viewer chooses another value. */
export const DEFAULT_THRESHOLD = 0.5;
export const THRESHOLD_STEP = 0.05;
/** What a metric has to change between two runs to count as worse or better. */
export const DEFAULT_DROP = 0.05;

export const KIND_LABELS: Record<RunKind, string> = {
  QUALITY: 'Calidad de respuesta',
  RETRIEVAL: 'Recuperación',
};

const FIXED_LABELS: Record<string, string> = {
  faithfulness: 'Fidelidad',
  answer_relevancy: 'Relevancia de la respuesta',
  context_precision: 'Precisión del contexto',
  reciprocal_rank: 'MRR',
};

const AT_K = /^(hit|recall)_at_(\d+)$/;

/** The label in Spanish of a metric; an unknown metric is shown with its own name. */
export function metricLabel(metric: string): string {
  const fixed = FIXED_LABELS[metric];
  if (fixed) {
    return fixed;
  }
  const match = AT_K.exec(metric);
  if (match) {
    return `${match[1] === 'hit' ? 'Hit rate' : 'Recall'}@${match[2]}`;
  }
  return metric;
}
