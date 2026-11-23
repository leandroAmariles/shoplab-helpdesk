import { RetrievalConfig, StoredResult } from './eval-history';

/**
 * What makes a case fail. It is informative: it blocks nothing (the thresholds that block a measurement belong to the
 * evaluation in CI). In quality a case fails when any metric with a value is strictly below a threshold the viewer
 * chooses; in retrieval, when it has expected documents and none was found within the chosen k.
 */
export type FailureCriterion =
  | { kind: 'QUALITY'; threshold: number }
  | { kind: 'RETRIEVAL'; k: number };

export interface FailureFilters {
  category: string | null;
  /** The metric that has to be among the failing ones (quality only). */
  metric: string | null;
}

export type FailureSort = 'worst' | 'caseId' | 'category';

export interface FailingCase {
  result: StoredResult;
  /** The metrics that put the case below the criterion. */
  failing: string[];
  /** The lowest of the failing values: what orders the list. */
  worst: number;
}

/** The metrics of the case with a value strictly below the threshold, in the order of the scores. */
export function failingMetrics(result: StoredResult, threshold: number): string[] {
  return Object.entries(result.scores)
    .filter(([, value]) => value !== null && value < threshold)
    .map(([metric]) => metric);
}

export function isFailingQuality(result: StoredResult, threshold: number): boolean {
  return failingMetrics(result, threshold).length > 0;
}

export function isFailingRetrieval(result: StoredResult, k: number): boolean {
  return result.expectedDocuments.length > 0 && result.scores[`hit_at_${k}`] === 0;
}

/** The largest k asked in a retrieval run, or `null` when it did not ask any. */
export function maxK(config: RetrievalConfig): number | null {
  const ks = config.ks ?? [];
  return ks.length === 0 ? null : Math.max(...ks);
}

function evaluate(result: StoredResult, criterion: FailureCriterion): FailingCase | null {
  if (criterion.kind === 'QUALITY') {
    const failing = failingMetrics(result, criterion.threshold);
    if (failing.length === 0) {
      return null;
    }
    const values = failing.map((metric) => result.scores[metric] as number);
    return { result, failing, worst: Math.min(...values) };
  }
  return isFailingRetrieval(result, criterion.k)
    ? { result, failing: [`hit_at_${criterion.k}`], worst: 0 }
    : null;
}

function compare(sort: FailureSort): (a: FailingCase, b: FailingCase) => number {
  const byId = (a: FailingCase, b: FailingCase) => a.result.caseId.localeCompare(b.result.caseId);
  switch (sort) {
    case 'caseId':
      return byId;
    case 'category':
      return (a, b) => a.result.category.localeCompare(b.result.category) || byId(a, b);
    default:
      return (a, b) => a.worst - b.worst || byId(a, b);
  }
}

/** The cases that fail, filtered and ordered; the order is stable. */
export function selectFailures(
  results: StoredResult[],
  criterion: FailureCriterion,
  filters: FailureFilters,
  sort: FailureSort,
): FailingCase[] {
  return results
    .filter((result) => filters.category === null || result.category === filters.category)
    .map((result) => evaluate(result, criterion))
    .filter((failure): failure is FailingCase => failure !== null)
    .filter(
      (failure) =>
        filters.metric === null ||
        criterion.kind === 'RETRIEVAL' ||
        failure.failing.includes(filters.metric),
    )
    .sort(compare(sort));
}

/** How many cases fail, whatever the filters. */
export function failureCount(results: StoredResult[], criterion: FailureCriterion): number {
  return results.filter((result) => evaluate(result, criterion) !== null).length;
}

export function summarizeFailures(failing: number, measured: number): string {
  if (failing === 0) {
    return `Ningún caso falla con este criterio (${measured} medidos)`;
  }
  return `${failing} ${failing === 1 ? 'caso falla' : 'casos fallan'} de ${measured} medidos`;
}
