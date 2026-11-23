import { metricLabel, RunKind, RunListItem } from './eval-history';

/** What a metric gave in one run: `value` is `null` when it has no value (a gap, never a zero). */
export interface SeriesPoint {
  runId: string;
  startedAt: string;
  value: number | null;
  /** The cases the value was computed with; `0` when there is no value. */
  n: number;
}

export interface Series {
  metric: string;
  label: string;
  points: SeriesPoint[];
}

export const NO_VALUE = 'sin valor';

const QUALITY_ORDER = ['faithfulness', 'answer_relevancy', 'context_precision'];
const AT_K = /^(hit|recall)_at_(\d+)$/;

/** Fixed order of the known metrics; the unknown ones go last, alphabetically. */
function rank(metric: string): [number, number, string] {
  const quality = QUALITY_ORDER.indexOf(metric);
  if (quality >= 0) {
    return [0, quality, metric];
  }
  if (metric === 'reciprocal_rank') {
    return [1, 0, metric];
  }
  const match = AT_K.exec(metric);
  if (match) {
    return [match[1] === 'hit' ? 2 : 3, Number(match[2]), metric];
  }
  return [4, 0, metric];
}

function compareMetrics(a: string, b: string): number {
  const [groupA, orderA, nameA] = rank(a);
  const [groupB, orderB, nameB] = rank(b);
  return groupA - groupB || orderA - orderB || nameA.localeCompare(nameB);
}

/** The names without repetitions, in the fixed order of the metrics. */
export function orderMetrics(names: Iterable<string>): string[] {
  return [...new Set(names)].sort(compareMetrics);
}

/** The metrics that any of the runs has, in a stable order. A metric no run could compute is still listed. */
export function availableMetrics(runs: RunListItem[]): string[] {
  return orderMetrics(runs.flatMap((run) => Object.keys(run.metrics)));
}

/** One series per metric, with one point per run in the order of the runs; what a run lacks is `null`. */
export function buildSeries(runs: RunListItem[], metrics: string[]): Series[] {
  return metrics.map((metric) => ({
    metric,
    label: metricLabel(metric),
    points: runs.map((run) => {
      const stat = run.metrics[metric];
      return {
        runId: run.runId,
        startedAt: run.startedAt,
        value: stat?.mean ?? null,
        n: stat?.mean == null ? 0 : stat.n,
      };
    }),
  }));
}

/** The metrics drawn before the viewer chooses: the faithfulness, or the MRR and the hit rate of the largest k. */
export function defaultMetrics(kind: RunKind, runs: RunListItem[]): string[] {
  if (kind === 'QUALITY') {
    return ['faithfulness'];
  }
  const hitRates = availableMetrics(runs).filter((metric) => metric.startsWith('hit_at_'));
  const largest = hitRates[hitRates.length - 1];
  return largest ? ['reciprocal_rank', largest] : ['reciprocal_rank'];
}

/** A value with four decimals and a comma, or «sin valor». */
export function formatValue(value: number | null): string {
  return value === null ? NO_VALUE : value.toFixed(4).replace('.', ',');
}

/** A difference with its sign and four decimals (`+0,0500`, `−0,0334`), or «sin valor». */
export function formatDelta(value: number | null): string {
  if (value === null) {
    return NO_VALUE;
  }
  const rounded = Number(value.toFixed(4));
  const text = Math.abs(rounded).toFixed(4).replace('.', ',');
  if (rounded > 0) {
    return `+${text}`;
  }
  return rounded < 0 ? `−${text}` : text;
}

/** A sentence that summarizes a series for assistive technology. */
export function describeSeries(series: Series): string {
  const count = series.points.length;
  const runs = `${count} ${count === 1 ? 'ejecución' : 'ejecuciones'}`;
  const values = series.points
    .map((point) => point.value)
    .filter((value): value is number => value !== null);
  if (values.length === 0) {
    return `${series.label}: ${runs}, sin valores`;
  }
  return `${series.label}: ${runs}, de ${formatValue(Math.min(...values))} a ${formatValue(Math.max(...values))}`;
}
