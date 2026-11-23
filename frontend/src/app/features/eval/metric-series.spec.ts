import { MetricStat, RunListItem, RunKind } from './eval-history';
import {
  availableMetrics,
  buildSeries,
  defaultMetrics,
  describeSeries,
  formatDelta,
  formatValue,
} from './metric-series';

function stat(mean: number | null, n = mean === null ? 0 : 96): MetricStat {
  return { mean, n };
}

function run(
  runId: string,
  metrics: Record<string, MetricStat>,
  kind: RunKind = 'QUALITY',
  startedAt = '2026-11-21T14:00:00Z',
): RunListItem {
  return {
    runId,
    kind,
    startedAt,
    finishedAt: startedAt,
    origin: 'local',
    commitSha: null,
    assistantModel: null,
    prompts: [],
    casesMeasured: 96,
    metrics,
  };
}

describe('metric-series', () => {
  describe('availableMetrics', () => {
    it('is the union of the metrics of the runs, quality ones in their fixed order', () => {
      const runs = [
        run('a', { context_precision: stat(0.5), faithfulness: stat(0.7) }),
        run('b', { answer_relevancy: stat(0.8), faithfulness: stat(0.7) }),
      ];

      expect(availableMetrics(runs)).toEqual([
        'faithfulness',
        'answer_relevancy',
        'context_precision',
      ]);
    });

    it('puts reciprocal_rank first in retrieval and then the metrics of each k from the smallest', () => {
      const runs = [
        run(
          'a',
          { recall_at_10: stat(0.9), hit_at_3: stat(0.6), reciprocal_rank: stat(0.5) },
          'RETRIEVAL',
        ),
        run(
          'b',
          { hit_at_1: stat(0.4), recall_at_3: stat(0.7), hit_at_10: stat(0.8) },
          'RETRIEVAL',
        ),
      ];

      expect(availableMetrics(runs)).toEqual([
        'reciprocal_rank',
        'hit_at_1',
        'hit_at_3',
        'hit_at_10',
        'recall_at_3',
        'recall_at_10',
      ]);
    });

    it('keeps a metric that no run could compute (it is a gap, but it exists)', () => {
      expect(availableMetrics([run('a', { faithfulness: stat(null) })])).toEqual(['faithfulness']);
    });

    it('puts an unknown metric last, alphabetically, and is empty without runs', () => {
      const runs = [run('a', { zeta: stat(1), faithfulness: stat(0.7), alpha: stat(0.1) })];

      expect(availableMetrics(runs)).toEqual(['faithfulness', 'alpha', 'zeta']);
      expect(availableMetrics([])).toEqual([]);
    });
  });

  describe('buildSeries', () => {
    const runs = [
      run('a', { faithfulness: stat(0.7, 90) }),
      run('b', { faithfulness: stat(null) }),
      run('c', { answer_relevancy: stat(0.8) }),
      run('d', { faithfulness: stat(0, 12) }),
    ];

    it('gives one point per run, in the order of the runs', () => {
      const [series] = buildSeries(runs, ['faithfulness']);

      expect(series.metric).toBe('faithfulness');
      expect(series.label).toBe('Fidelidad');
      expect(series.points.map((point) => point.runId)).toEqual(['a', 'b', 'c', 'd']);
    });

    it('never turns a missing value into a zero: no value, or no metric, is null', () => {
      const [series] = buildSeries(runs, ['faithfulness']);

      expect(series.points.map((point) => point.value)).toEqual([0.7, null, null, 0]);
      expect(series.points.map((point) => point.n)).toEqual([90, 0, 0, 12]);
    });

    it('keeps a real zero as a zero', () => {
      const [series] = buildSeries(runs, ['faithfulness']);

      expect(series.points[3].value).toBe(0);
    });

    it('builds a series for each chosen metric, in the order chosen', () => {
      const series = buildSeries(runs, ['answer_relevancy', 'faithfulness']);

      expect(series.map((s) => s.metric)).toEqual(['answer_relevancy', 'faithfulness']);
    });

    it('has no series for no metrics and empty points for no runs', () => {
      expect(buildSeries(runs, [])).toEqual([]);
      expect(buildSeries([], ['faithfulness'])[0].points).toEqual([]);
    });
  });

  describe('defaultMetrics', () => {
    it('is the faithfulness in quality, with or without runs', () => {
      expect(defaultMetrics('QUALITY', [run('a', { faithfulness: stat(0.7) })])).toEqual([
        'faithfulness',
      ]);
      expect(defaultMetrics('QUALITY', [])).toEqual(['faithfulness']);
    });

    it('is reciprocal_rank and the hit rate of the largest k in retrieval', () => {
      const runs = [
        run(
          'a',
          { reciprocal_rank: stat(0.5), hit_at_1: stat(0.4), hit_at_5: stat(0.8) },
          'RETRIEVAL',
        ),
        run(
          'b',
          { reciprocal_rank: stat(0.6), hit_at_3: stat(0.7), hit_at_10: stat(0.9) },
          'RETRIEVAL',
        ),
      ];

      expect(defaultMetrics('RETRIEVAL', runs)).toEqual(['reciprocal_rank', 'hit_at_10']);
    });

    it('is only reciprocal_rank in retrieval when no k is known', () => {
      expect(defaultMetrics('RETRIEVAL', [])).toEqual(['reciprocal_rank']);
    });
  });

  describe('formatValue', () => {
    it('shows four decimals with a comma, and "sin valor" for null', () => {
      expect(formatValue(0.71344)).toBe('0,7134');
      expect(formatValue(1)).toBe('1,0000');
      expect(formatValue(0)).toBe('0,0000');
      expect(formatValue(null)).toBe('sin valor');
    });
  });

  describe('formatDelta', () => {
    it('shows the sign of a change with four decimals and a comma', () => {
      expect(formatDelta(0.05)).toBe('+0,0500');
      expect(formatDelta(-0.0334)).toBe('−0,0334');
    });

    it('shows no sign for no change, not even for a change that rounds to none', () => {
      expect(formatDelta(0)).toBe('0,0000');
      expect(formatDelta(-0.00001)).toBe('0,0000');
    });

    it('says «sin valor» for null, never a zero', () => {
      expect(formatDelta(null)).toBe('sin valor');
    });
  });

  describe('describeSeries', () => {
    it('says how many runs there are and the range of the values, ignoring the gaps', () => {
      const [series] = buildSeries(
        [
          run('a', { faithfulness: stat(0.69) }),
          run('b', { faithfulness: stat(null) }),
          run('c', { faithfulness: stat(0.78) }),
        ],
        ['faithfulness'],
      );

      expect(describeSeries(series)).toBe('Fidelidad: 3 ejecuciones, de 0,6900 a 0,7800');
    });

    it('says there are no values when every point is a gap', () => {
      const [series] = buildSeries([run('a', { faithfulness: stat(null) })], ['faithfulness']);

      expect(describeSeries(series)).toBe('Fidelidad: 1 ejecución, sin valores');
    });

    it('uses the singular for one run with a value', () => {
      const [series] = buildSeries([run('a', { faithfulness: stat(0.5) })], ['faithfulness']);

      expect(describeSeries(series)).toBe('Fidelidad: 1 ejecución, de 0,5000 a 0,5000');
    });
  });
});
