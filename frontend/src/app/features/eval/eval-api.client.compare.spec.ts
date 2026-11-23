import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { EVAL_HISTORY_URL, EvalApiClient } from './eval-api.client';
import { RunComparison } from './eval-history';
import { COMPARISON_BODY } from './eval-test-data';

describe('EvalApiClient.compare', () => {
  let client: EvalApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(EvalApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for the two runs, the metric and the tolerance and handles its own errors', () => {
    client.compare('t031-1', 't031-2', 'faithfulness', 0.05).subscribe();

    const request = http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`);
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('base')).toBe('t031-1');
    expect(request.request.params.get('target')).toBe('t031-2');
    expect(request.request.params.get('metric')).toBe('faithfulness');
    expect(request.request.params.get('drop')).toBe('0.05');
    expect(request.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    request.flush(COMPARISON_BODY);
  });

  describe('translated comparison', () => {
    let comparison: RunComparison;

    beforeEach(() => {
      client.compare('t031-1', 't031-2', 'faithfulness', 0.05).subscribe((v) => (comparison = v));
      http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`).flush(COMPARISON_BODY);
    });

    it('has the runs in camelCase', () => {
      expect(comparison.base.runId).toBe('t031-1');
      expect(comparison.target.startedAt).toBe('2026-11-22T10:00:00Z');
      expect(comparison.metric).toBe('faithfulness');
      expect(comparison.drop).toBe(0.05);
    });

    it('keeps a metric without mean as null and never as zero', () => {
      const precision = comparison.metrics.find((m) => m.metric === 'context_precision');
      expect(precision?.base).toEqual({ mean: null, n: 0 });
      expect(precision?.delta).toBeNull();
      expect(comparison.metrics[0].delta).toBe(-0.0334);
    });

    it('translates the parameters, the warnings and the counts', () => {
      expect(comparison.parameters[1]).toEqual({
        field: 'judge.temperature',
        base: '0.0',
        target: '0.2',
        affectsComparability: true,
      });
      expect(comparison.parameters[2].base).toBeNull();
      expect(comparison.warnings).toEqual(['judge.temperature']);
      expect(comparison.counts).toEqual({
        worse: 1,
        better: 1,
        same: 0,
        noValue: 1,
        notComparable: 1,
        onlyBase: 1,
        onlyTarget: 1,
      });
    });

    it('has the values of a compared case and null where the answer says nothing', () => {
      expect(comparison.cases[0]).toEqual({
        caseId: 'G044',
        category: 'returns',
        question: '¿Cuántos días tengo para devolver un producto?',
        status: 'WORSE',
        baseValue: 0.8,
        targetValue: 0.2,
        delta: -0.6,
      });
      const notComparable = comparison.cases.find((c) => c.caseId === 'G030');
      expect(notComparable?.baseValue).toBeNull();
      expect(notComparable?.targetValue).toBeNull();
      expect(notComparable?.delta).toBeNull();
    });
  });
});
