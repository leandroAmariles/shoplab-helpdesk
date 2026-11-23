import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { EVAL_HISTORY_URL } from './eval-api.client';
import { RUN_BODY } from './eval-test-data';
import { DETAIL_ERROR_MESSAGE, EvalStore } from './eval-store';

const RETRIEVAL_BODY = {
  ...RUN_BODY,
  run: {
    ...RUN_BODY.run,
    run_id: 'ret-1',
    kind: 'RETRIEVAL',
    judge: null,
    evaluator: null,
    retrieval_config: {
      top_k: 4,
      similarity_threshold: 0.6,
      max_context_characters: 8000,
      depth: 50,
      ks: [1, 3, 5],
    },
  },
  results: RUN_BODY.results.map((result) => ({
    ...result,
    scores: { hit_at_1: 0, hit_at_3: 0, hit_at_5: 1, reciprocal_rank: 0.25 },
  })),
};

describe('EvalStore (run detail)', () => {
  let store: EvalStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        EvalStore,
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: () => undefined } },
      ],
    });
    store = TestBed.inject(EvalStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function runRequest(id: string): TestRequest {
    return http.expectOne(`${EVAL_HISTORY_URL}/runs/${id}`);
  }

  it('loads a run when it is chosen and shows it', () => {
    store.selectRun('t031-1');
    expect(store.selectedRunId()).toBe('t031-1');
    expect(store.detailLoading()).toBe(true);

    runRequest('t031-1').flush(RUN_BODY);

    expect(store.detailLoading()).toBe(false);
    expect(store.detail()?.run.runId).toBe('t031-1');
    expect(store.detailError()).toBeNull();
  });

  it('keeps a run it already read: choosing it again asks for nothing, because a run never changes', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);
    store.clearSelection();
    expect(store.detail()).toBeNull();

    store.selectRun('t031-1');

    http.expectNone(`${EVAL_HISTORY_URL}/runs/t031-1`);
    expect(store.detail()?.run.runId).toBe('t031-1');
  });

  it('ignores an id that is not the format of a run id and asks for nothing', () => {
    store.selectRun('a b/../c');

    http.expectNone(() => true);
    expect(store.selectedRunId()).toBeNull();
  });

  it('starts the quality criterion at a threshold of 0.5 and the retrieval one at the largest k', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);
    expect(store.failureCriterion()).toEqual({ kind: 'QUALITY', threshold: 0.5 });

    store.selectRun('ret-1');
    runRequest('ret-1').flush(RETRIEVAL_BODY);
    expect(store.failureCriterion()).toEqual({ kind: 'RETRIEVAL', k: 5 });
    expect(store.availableKs()).toEqual([1, 3, 5]);
  });

  it('changing the threshold or the k changes the failing cases without any request', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);
    expect(store.failures().map((f) => f.result.caseId)).toEqual(['G044']);

    store.setThreshold(0.1);
    expect(store.failures()).toEqual([]);
    store.setThreshold(1);
    expect(store.failures().map((f) => f.result.caseId)).toEqual(['G044']);

    store.selectRun('ret-1');
    runRequest('ret-1').flush(RETRIEVAL_BODY);
    expect(store.failures()).toEqual([]);
    store.setK(3);
    expect(store.failures().map((f) => f.result.caseId)).toEqual(['G044']);
    http.expectNone(() => true);
  });

  it('keeps the threshold between 0 and 1', () => {
    store.setThreshold(5);
    expect(store.threshold()).toBe(1);
    store.setThreshold(-2);
    expect(store.threshold()).toBe(0);
    store.setThreshold(Number.NaN);
    expect(store.threshold()).toBe(0.5);
    store.setThreshold(0.123456);
    expect(store.threshold()).toBe(0.12);
  });

  it('says how many cases fail out of how many were measured', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);

    expect(store.failureSummary()).toBe('1 caso falla de 96 medidos');
    store.setThreshold(0);
    expect(store.failureSummary()).toBe('Ningún caso falla con este criterio (96 medidos)');
  });

  it('filters the failing cases by category and by metric and changes the order', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);
    store.setThreshold(1);

    store.setCategoryFilter('payments');
    expect(store.failures()).toEqual([]);
    store.setCategoryFilter(null);
    store.setMetricFilter('answer_relevancy');
    expect(store.failures()).toEqual([]);
    store.setMetricFilter(null);
    store.setSort('caseId');

    expect(store.sort()).toBe('caseId');
    expect(store.failures().length).toBeGreaterThan(0);
  });

  it('a run that does not exist is reported and the selection is cleared', () => {
    store.selectRun('t031-9');
    runRequest('t031-9').flush({ code: 'SLHD_EVAL_00004' }, { status: 404, statusText: 'Not Found' });

    expect(store.runNotFound()).toBe(true);
    expect(store.selectedRunId()).toBeNull();
    expect(store.detail()).toBeNull();
    expect(store.detailError()).toBeNull();
  });

  it('a network error of the detail is its own error and does not touch the list', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(null, { status: 0, statusText: 'Network' });

    expect(store.detailError()).toBe(DETAIL_ERROR_MESSAGE);
    expect(store.detailLoading()).toBe(false);
    expect(store.loadError()).toBeNull();
    expect(store.selectedRunId()).toBe('t031-1');
  });

  it('retries the detail after an error', () => {
    store.selectRun('t031-1');
    runRequest('t031-1').flush(null, { status: 0, statusText: 'Network' });

    store.retryDetail();
    runRequest('t031-1').flush(RUN_BODY);

    expect(store.detailError()).toBeNull();
    expect(store.detail()?.run.runId).toBe('t031-1');
  });

  it('forgets the not-found notice when another run is chosen', () => {
    store.selectRun('t031-9');
    runRequest('t031-9').flush({}, { status: 404, statusText: 'Not Found' });

    store.selectRun('t031-1');
    runRequest('t031-1').flush(RUN_BODY);

    expect(store.runNotFound()).toBe(false);
  });

  it('a late answer of the run that is no longer chosen does not replace the current one', () => {
    store.selectRun('t031-1');
    const stale = runRequest('t031-1');
    store.selectRun('ret-1');
    const current = runRequest('ret-1');

    expect(stale.cancelled).toBe(true);
    current.flush(RETRIEVAL_BODY);

    expect(store.detail()?.run.runId).toBe('ret-1');
  });
});
