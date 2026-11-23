import {
  failingMetrics,
  failureCount,
  isFailingQuality,
  isFailingRetrieval,
  maxK,
  selectFailures,
  summarizeFailures,
} from './case-failures';
import { RetrievalConfig, StoredResult } from './eval-history';

function result(
  caseId: string,
  scores: Record<string, number | null>,
  extra: Partial<StoredResult> = {},
): StoredResult {
  return {
    caseId,
    revision: 'r'.repeat(64),
    category: 'returns',
    question: `Pregunta ${caseId}`,
    referenceAnswer: 'Respuesta de referencia',
    expectedDocuments: ['politica-devoluciones'],
    caseKind: 'answered',
    scores,
    retrievedDocuments: [],
    abstained: false,
    traceId: null,
    traceUrl: null,
    ...extra,
  };
}

describe('case-failures', () => {
  describe('isFailingQuality', () => {
    it('fails when any metric with a value is below the threshold', () => {
      const failing = result('G1', {
        faithfulness: 0.9,
        answer_relevancy: 0.4,
        context_precision: 0.8,
      });

      expect(isFailingQuality(failing, 0.5)).toBe(true);
      expect(failingMetrics(failing, 0.5)).toEqual(['answer_relevancy']);
    });

    it('does not fail when a value is exactly the threshold: only strictly below counts', () => {
      expect(isFailingQuality(result('G1', { faithfulness: 0.5 }), 0.5)).toBe(false);
      expect(isFailingQuality(result('G1', { faithfulness: 0.4999 }), 0.5)).toBe(true);
    });

    it('does not count a metric without value, and a case with none does not fail', () => {
      const empty = result('G1', { faithfulness: null, answer_relevancy: null });

      expect(isFailingQuality(empty, 0.9)).toBe(false);
      expect(failingMetrics(empty, 0.9)).toEqual([]);
      expect(isFailingQuality(result('G2', {}), 0.9)).toBe(false);
    });

    it('treats a real zero as a score that is below any positive threshold', () => {
      expect(isFailingQuality(result('G1', { faithfulness: 0 }), 0.05)).toBe(true);
    });

    it('lists every metric below the threshold, not only the worst', () => {
      const several = result('G1', {
        faithfulness: 0.1,
        answer_relevancy: 0.3,
        context_precision: 0.9,
      });

      expect(failingMetrics(several, 0.5)).toEqual(['faithfulness', 'answer_relevancy']);
    });

    it('fails nothing at threshold 0 and every case with a value below 1 at threshold 1', () => {
      const results = [
        result('G1', { faithfulness: 0 }),
        result('G2', { faithfulness: 0.99 }),
        result('G3', { faithfulness: 1 }),
      ];

      expect(results.filter((r) => isFailingQuality(r, 0))).toEqual([]);
      expect(results.filter((r) => isFailingQuality(r, 1)).map((r) => r.caseId)).toEqual([
        'G1',
        'G2',
      ]);
    });
  });

  describe('isFailingRetrieval', () => {
    it('fails when the case has expected documents and none was found within k', () => {
      const miss = result('G1', { hit_at_1: 0, hit_at_3: 1, hit_at_5: 1 });

      expect(isFailingRetrieval(miss, 1)).toBe(true);
      expect(isFailingRetrieval(miss, 3)).toBe(false);
    });

    it('does not fail a case without expected documents, nor one whose hit has no value', () => {
      expect(isFailingRetrieval(result('G1', { hit_at_3: 0 }, { expectedDocuments: [] }), 3)).toBe(
        false,
      );
      expect(isFailingRetrieval(result('G2', { hit_at_3: null }), 3)).toBe(false);
      expect(isFailingRetrieval(result('G3', {}), 3)).toBe(false);
    });
  });

  describe('maxK', () => {
    const config = (ks: number[] | null): RetrievalConfig => ({
      topK: 4,
      similarityThreshold: 0.6,
      maxContextCharacters: 8000,
      depth: 50,
      ks,
    });

    it('is the largest k asked in the run', () => {
      expect(maxK(config([5, 1, 10, 3]))).toBe(10);
    });

    it('is null when the run has no k', () => {
      expect(maxK(config(null))).toBeNull();
      expect(maxK(config([]))).toBeNull();
    });
  });

  describe('selectFailures', () => {
    const results = [
      result('G3', { faithfulness: 0.4, answer_relevancy: 0.9 }, { category: 'payments' }),
      result('G1', { faithfulness: 0.1, answer_relevancy: 0.2 }),
      result('G2', { faithfulness: 0.9, answer_relevancy: 0.45 }, { category: 'payments' }),
      result('G4', { faithfulness: 0.95, answer_relevancy: 0.9 }),
      result('G5', { faithfulness: null, answer_relevancy: null }),
    ];
    const quality = { kind: 'QUALITY', threshold: 0.5 } as const;
    const all = { category: null, metric: null };

    it('keeps only the failing cases, the worst first by default', () => {
      const failures = selectFailures(results, quality, all, 'worst');

      expect(failures.map((f) => f.result.caseId)).toEqual(['G1', 'G3', 'G2']);
      expect(failures[0].failing).toEqual(['faithfulness', 'answer_relevancy']);
      expect(failures[0].worst).toBe(0.1);
    });

    it('breaks the ties of the worst value by case id, so the order is stable', () => {
      const tied = [result('G9', { faithfulness: 0.2 }), result('G2', { faithfulness: 0.2 })];

      expect(selectFailures(tied, quality, all, 'worst').map((f) => f.result.caseId)).toEqual([
        'G2',
        'G9',
      ]);
    });

    it('sorts by case id or by category and then case id', () => {
      expect(selectFailures(results, quality, all, 'caseId').map((f) => f.result.caseId)).toEqual([
        'G1',
        'G2',
        'G3',
      ]);
      expect(selectFailures(results, quality, all, 'category').map((f) => f.result.caseId)).toEqual(
        ['G2', 'G3', 'G1'],
      );
    });

    it('filters by category', () => {
      const failures = selectFailures(
        results,
        quality,
        { category: 'payments', metric: null },
        'worst',
      );

      expect(failures.map((f) => f.result.caseId)).toEqual(['G3', 'G2']);
    });

    it('filters by the metric that failed', () => {
      const failures = selectFailures(
        results,
        quality,
        { category: null, metric: 'answer_relevancy' },
        'worst',
      );

      expect(failures.map((f) => f.result.caseId)).toEqual(['G1', 'G2']);
    });

    it('has no failures when the threshold is 0, and counts cases that fail', () => {
      expect(selectFailures(results, { kind: 'QUALITY', threshold: 0 }, all, 'worst')).toEqual([]);
      expect(failureCount(results, quality)).toBe(3);
    });

    it('selects the cases that did not find their documents within k in retrieval', () => {
      const retrieval = [
        result('G1', { hit_at_3: 0 }),
        result('G2', { hit_at_3: 1 }),
        result('G3', { hit_at_3: 0 }, { expectedDocuments: [] }),
        result('G4', { hit_at_3: 0 }, { category: 'payments' }),
      ];

      const failures = selectFailures(retrieval, { kind: 'RETRIEVAL', k: 3 }, all, 'worst');

      expect(failures.map((f) => f.result.caseId)).toEqual(['G1', 'G4']);
      expect(failures[0].failing).toEqual(['hit_at_3']);
      expect(failureCount(retrieval, { kind: 'RETRIEVAL', k: 3 })).toBe(2);
    });
  });

  describe('summarizeFailures', () => {
    it('says how many cases fail out of how many were measured', () => {
      expect(summarizeFailures(3, 96)).toBe('3 casos fallan de 96 medidos');
      expect(summarizeFailures(1, 96)).toBe('1 caso falla de 96 medidos');
    });

    it('says explicitly that no case fails', () => {
      expect(summarizeFailures(0, 96)).toBe('Ningún caso falla con este criterio (96 medidos)');
    });
  });
});
