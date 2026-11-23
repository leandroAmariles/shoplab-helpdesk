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
import { EvalStore, LOAD_ERROR_MESSAGE } from './eval-store';
import { RunKind } from './eval-history';

interface WireOptions {
  kind?: RunKind;
  total?: number;
  totalAllOrigins?: number;
  runs?: string[];
  metrics?: Record<string, { mean: number | null; n: number }>;
}

function listing(options: WireOptions = {}) {
  const kind = options.kind ?? 'QUALITY';
  const ids = options.runs ?? ['a', 'b', 'c'];
  return {
    kind,
    shown: ids.length,
    total: options.total ?? ids.length,
    total_all_origins: options.totalAllOrigins ?? options.total ?? ids.length,
    origins: [
      { origin: 'dev', count: 2 },
      { origin: 'local', count: 1 },
    ],
    runs: ids.map((id, index) => ({
      run_id: id,
      kind,
      started_at: `2026-11-2${index + 1}T10:00:00Z`,
      finished_at: `2026-11-2${index + 1}T10:10:00Z`,
      origin: 'dev',
      commit_sha: null,
      assistant_model: null,
      prompts: [],
      cases_measured: 96,
      metrics: options.metrics ?? {
        faithfulness: { mean: 0.7, n: 96 },
        answer_relevancy: { mean: 0.8, n: 90 },
        context_precision: { mean: 0.6, n: 90 },
      },
    })),
  };
}

describe('EvalStore (listing)', () => {
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

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function listRequest(): TestRequest {
    return http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`);
  }

  it('starts with quality, the faithfulness, 100 runs and nothing loaded', () => {
    expect(store.kind()).toBe('QUALITY');
    expect(store.selectedMetrics()).toEqual(['faithfulness']);
    expect(store.limit()).toBe(100);
    expect(store.loaded()).toBe(false);
    expect(store.isEmpty()).toBe(false);
  });

  it('loads the listing of the kind and marks it loaded', () => {
    store.loadListing();
    expect(store.loading()).toBe(true);

    const request = listRequest();
    expect(request.request.params.get('kind')).toBe('QUALITY');
    expect(request.request.params.get('limit')).toBe('100');
    request.flush(listing());

    expect(store.loading()).toBe(false);
    expect(store.loaded()).toBe(true);
    expect(store.runs().map((run) => run.runId)).toEqual(['a', 'b', 'c']);
    expect(store.series()).toHaveLength(1);
    expect(store.series()[0].points.map((point) => point.value)).toEqual([0.7, 0.7, 0.7]);
  });

  it('says it is empty only after the list was read and no run of the kind exists', () => {
    store.loadListing();
    expect(store.isEmpty()).toBe(false);
    listRequest().flush(listing({ runs: [], total: 0, totalAllOrigins: 0 }));

    expect(store.isEmpty()).toBe(true);
    expect(store.isEmptyByFilter()).toBe(false);
  });

  it('tells apart an empty history from an origin filter that hides every run', () => {
    store.loadListing();
    listRequest().flush(listing());
    store.setOrigin('prod');
    listRequest().flush(listing({ runs: [], total: 0, totalAllOrigins: 3 }));

    expect(store.isEmpty()).toBe(false);
    expect(store.isEmptyByFilter()).toBe(true);
    expect(store.hiddenByOrigin()).toBe(3);
  });

  it('changing the kind clears the series, asks for the runs of the new kind and never mixes them', () => {
    store.loadListing();
    listRequest().flush(listing());
    expect(store.runs()).toHaveLength(3);

    store.setKind('RETRIEVAL');

    expect(store.runs()).toEqual([]);
    expect(store.series()).toEqual([]);
    const request = listRequest();
    expect(request.request.params.get('kind')).toBe('RETRIEVAL');
    request.flush(
      listing({
        kind: 'RETRIEVAL',
        runs: ['r1'],
        metrics: { reciprocal_rank: { mean: 0.5, n: 90 }, hit_at_5: { mean: 0.9, n: 80 } },
      }),
    );

    expect(store.runs().map((run) => run.kind)).toEqual(['RETRIEVAL']);
    expect(store.selectedMetrics()).toEqual(['reciprocal_rank', 'hit_at_5']);
  });

  it('a late answer of the previous kind does not overwrite the current one', () => {
    store.loadListing();
    const stale = listRequest();
    store.setKind('RETRIEVAL');
    const current = listRequest();

    expect(stale.cancelled).toBe(true);
    current.flush(
      listing({
        kind: 'RETRIEVAL',
        runs: ['r1'],
        metrics: { reciprocal_rank: { mean: 0.5, n: 9 } },
      }),
    );

    expect(store.runs().map((run) => run.runId)).toEqual(['r1']);
  });

  it('keeps the same kind without asking again', () => {
    store.loadListing();
    listRequest().flush(listing());

    store.setKind('QUALITY');

    http.expectNone(`${EVAL_HISTORY_URL}/runs`);
  });

  describe('metrics', () => {
    beforeEach(() => {
      store.loadListing();
      listRequest().flush(listing());
    });

    it('lists the metrics that the runs have', () => {
      expect(store.availableMetrics()).toEqual([
        'faithfulness',
        'answer_relevancy',
        'context_precision',
      ]);
    });

    it('adds and removes metrics, one series for each, in the order they were chosen', () => {
      store.toggleMetric('answer_relevancy');

      expect(store.selectedMetrics()).toEqual(['faithfulness', 'answer_relevancy']);
      expect(store.series().map((series) => series.metric)).toEqual([
        'faithfulness',
        'answer_relevancy',
      ]);

      store.toggleMetric('faithfulness');

      expect(store.selectedMetrics()).toEqual(['answer_relevancy']);
    });

    it('never leaves the chart without a metric', () => {
      store.toggleMetric('faithfulness');

      expect(store.selectedMetrics()).toEqual(['faithfulness']);
    });

    it('accepts at most five metrics at once', () => {
      store.loadListing();
      listRequest().flush(
        listing({
          kind: 'QUALITY',
          metrics: Object.fromEntries(
            [
              'faithfulness',
              'answer_relevancy',
              'context_precision',
              'm_four',
              'm_five',
              'm_six',
            ].map((name) => [name, { mean: 0.5, n: 9 }]),
          ),
        }),
      );
      ['answer_relevancy', 'context_precision', 'm_four', 'm_five', 'm_six'].forEach((name) =>
        store.toggleMetric(name),
      );

      expect(store.selectedMetrics()).toHaveLength(5);
      expect(store.selectedMetrics()).not.toContain('m_six');
    });
  });

  describe('origin and range', () => {
    it('filters by origin, asks again and tells how many runs the filter hides', () => {
      store.loadListing();
      listRequest().flush(listing({ total: 3, totalAllOrigins: 3 }));

      store.setOrigin('local');
      const request = listRequest();
      expect(request.request.params.get('origin')).toBe('local');
      request.flush(listing({ runs: ['c'], total: 1, totalAllOrigins: 3 }));

      expect(store.originFilter()).toBe('local');
      expect(store.hiddenByOrigin()).toBe(2);
      expect(store.runs().map((run) => run.runId)).toEqual(['c']);
    });

    it('removes the filter', () => {
      store.setOrigin('local');
      listRequest().flush(listing({ runs: ['c'], total: 1, totalAllOrigins: 3 }));

      store.setOrigin(null);

      expect(listRequest().request.params.has('origin')).toBe(false);
    });

    it('says how many runs the range leaves out and widens by steps up to 500', () => {
      store.loadListing();
      listRequest().flush(listing({ runs: ['a', 'b'], total: 400, totalAllOrigins: 400 }));

      expect(store.notShownByRange()).toBe(398);
      expect(store.canShowMore()).toBe(true);

      store.showMore();
      expect(listRequest().request.params.get('limit')).toBe('250');
      store.showMore();
      const last = listRequest();
      expect(last.request.params.get('limit')).toBe('500');
      last.flush(listing({ runs: ['a'], total: 400, totalAllOrigins: 400 }));

      expect(store.limit()).toBe(500);
      expect(store.canShowMore()).toBe(false);
    });

    it('does not offer more when every run is shown', () => {
      store.loadListing();
      listRequest().flush(listing());

      expect(store.notShownByRange()).toBe(0);
      expect(store.canShowMore()).toBe(false);
    });
  });

  describe('errors and refresh', () => {
    it('keeps what was shown when a reload fails, and says so', () => {
      store.loadListing();
      listRequest().flush(listing());

      store.reload();
      listRequest().flush({ code: 'SLHD_EVAL_00005' }, { status: 503, statusText: 'Unavailable' });

      expect(store.loadError()).toBe(LOAD_ERROR_MESSAGE);
      expect(store.runs()).toHaveLength(3);
      expect(store.loaded()).toBe(true);
      expect(store.loading()).toBe(false);
    });

    it('clears the error when a reload works', () => {
      store.loadListing();
      listRequest().flush(null, { status: 0, statusText: 'Network' });
      expect(store.loadError()).toBe(LOAD_ERROR_MESSAGE);

      store.reload();
      listRequest().flush(listing());

      expect(store.loadError()).toBeNull();
    });

    it('does not poll: time passing sends no request', () => {
      vi.useFakeTimers();
      store.loadListing();
      listRequest().flush(listing());

      vi.advanceTimersByTime(60 * 60 * 1000);

      http.expectNone(`${EVAL_HISTORY_URL}/runs`);
    });
  });
});
