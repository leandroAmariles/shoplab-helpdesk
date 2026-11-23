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
import { COMPARISON_BODY } from './eval-test-data';
import { COMPARISON_ERROR_MESSAGES, EvalStore } from './eval-store';

function listing(kind: 'QUALITY' | 'RETRIEVAL' = 'QUALITY', ids = ['t031-1', 't031-2', 't031-3']) {
  return {
    kind,
    shown: ids.length,
    total: ids.length,
    total_all_origins: ids.length,
    origins: [{ origin: 'dev', count: ids.length }],
    runs: ids.map((id, index) => ({
      run_id: id,
      kind,
      started_at: `2026-11-0${index + 1}T10:00:00Z`,
      finished_at: `2026-11-0${index + 1}T10:10:00Z`,
      origin: 'dev',
      commit_sha: null,
      assistant_model: null,
      prompts: [],
      cases_measured: 96,
      metrics: { faithfulness: { mean: 0.7, n: 96 } },
    })),
  };
}

describe('EvalStore (comparison)', () => {
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
    store.loadListing();
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`).flush(listing());
  });

  afterEach(() => http.verify());

  function compareRequest(): TestRequest {
    return http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`);
  }

  it('takes the run before it in the list as the reference when none was chosen', () => {
    expect(store.referenceFor('t031-2')).toBe('t031-1');
    expect(store.referenceFor('t031-3')).toBe('t031-2');
  });

  it('tells for each run whether it can be compared with the reference and, if not, why', () => {
    expect(store.compareAvailability()).toEqual({
      't031-1': 'no-reference',
      't031-2': 'available',
      't031-3': 'available',
    });

    store.setReference('t031-2');

    expect(store.compareAvailability()).toEqual({
      't031-1': 'available',
      't031-2': 'is-reference',
      't031-3': 'available',
    });
  });

  it('has no reference for the first run, so there is nothing to compare it with', () => {
    expect(store.referenceFor('t031-1')).toBeNull();
  });

  it('uses the reference the viewer chose over the one before', () => {
    store.setReference('t031-3');

    expect(store.referenceRunId()).toBe('t031-3');
    expect(store.referenceFor('t031-2')).toBe('t031-3');
  });

  it('asks for the comparison with the reference, the default metric and the default tolerance', () => {
    store.compareWith('t031-2');

    const request = compareRequest();
    expect(request.request.params.get('base')).toBe('t031-1');
    expect(request.request.params.get('target')).toBe('t031-2');
    expect(request.request.params.get('metric')).toBe('faithfulness');
    expect(request.request.params.get('drop')).toBe('0.05');
    expect(store.comparisonLoading()).toBe(true);
    request.flush(COMPARISON_BODY);

    expect(store.comparison()?.counts.worse).toBe(1);
    expect(store.comparisonLoading()).toBe(false);
    expect(store.comparisonError()).toBeNull();
  });

  it('does not ask again for a comparison that it already has', () => {
    store.compareWith('t031-2');
    compareRequest().flush(COMPARISON_BODY);

    store.compareWith('t031-2');
  });

  it('asks again when the metric or the tolerance changes', () => {
    store.compareWith('t031-2');
    compareRequest().flush(COMPARISON_BODY);

    store.setComparisonMetric('answer_relevancy');
    const byMetric = compareRequest();
    expect(byMetric.request.params.get('metric')).toBe('answer_relevancy');
    byMetric.flush({ ...COMPARISON_BODY, metric: 'answer_relevancy' });

    store.setComparisonDrop(0.1);
    const byDrop = compareRequest();
    expect(byDrop.request.params.get('drop')).toBe('0.1');
    byDrop.flush({ ...COMPARISON_BODY, drop: 0.1 });
    expect(store.comparison()?.drop).toBe(0.1);
  });

  it('keeps the tolerance between 0 and 1', () => {
    store.setComparisonDrop(7);
    expect(store.comparisonDrop()).toBe(1);

    store.setComparisonDrop(-1);
    expect(store.comparisonDrop()).toBe(0);

    store.setComparisonDrop(Number.NaN);
    expect(store.comparisonDrop()).toBe(0.05);
  });

  it('does not compare a run with itself', () => {
    store.setReference('t031-2');

    store.compareWith('t031-2');
  });

  it('can compare two runs of the same kind and not a run with itself or an unknown one', () => {
    expect(store.canCompare('t031-1', 't031-2')).toBe(true);
    expect(store.canCompare('t031-1', 't031-1')).toBe(false);
    expect(store.canCompare('t031-1', 'unknown')).toBe(false);
  });

  it('tells that the runs are not of the same kind and leaves the rest as it was', () => {
    store.compareWith('t031-2');
    compareRequest().flush(
      { code: 'SLHD_EVAL_00006' },
      { status: 422, statusText: 'Unprocessable Content' },
    );

    expect(store.comparison()).toBeNull();
    expect(store.comparisonError()).toBe(COMPARISON_ERROR_MESSAGES.notComparable);
    expect(store.runs().length).toBe(3);
    expect(store.loadError()).toBeNull();
  });

  it('tells that a run does not exist', () => {
    store.compareWith('t031-2');
    compareRequest().flush({ code: 'SLHD_EVAL_00004' }, { status: 404, statusText: 'Not Found' });

    expect(store.comparisonError()).toBe(COMPARISON_ERROR_MESSAGES.notFound);
  });

  it('says that the comparison could not be read when the network fails and retries on demand', () => {
    store.compareWith('t031-2');
    compareRequest().flush(null, { status: 0, statusText: 'Network' });

    expect(store.comparisonError()).toBe(COMPARISON_ERROR_MESSAGES.other);

    store.retryComparison();
    compareRequest().flush(COMPARISON_BODY);
    expect(store.comparisonError()).toBeNull();
    expect(store.comparison()).not.toBeNull();
  });

  it('closes the comparison', () => {
    store.compareWith('t031-2');
    compareRequest().flush(COMPARISON_BODY);

    store.closeComparison();

    expect(store.comparison()).toBeNull();
    expect(store.comparisonTargetId()).toBeNull();
  });

  it('forgets the reference and the comparison when the kind changes', () => {
    store.setReference('t031-1');
    store.compareWith('t031-2');
    compareRequest().flush(COMPARISON_BODY);

    store.setKind('RETRIEVAL');
    http
      .expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`)
      .flush(listing('RETRIEVAL', ['r-1', 'r-2']));

    expect(store.comparison()).toBeNull();
    expect(store.referenceRunId()).toBeNull();
    expect(store.comparisonMetric()).toBe('reciprocal_rank');
  });
});
