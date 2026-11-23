import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import {
  failureCount,
  FailureCriterion,
  FailureSort,
  maxK,
  selectFailures,
  summarizeFailures,
} from './case-failures';
import { EvalApiClient } from './eval-api.client';
import {
  DEFAULT_KIND,
  DEFAULT_LIMIT,
  DEFAULT_THRESHOLD,
  LIMIT_STEPS,
  MAX_SELECTED_METRICS,
  RunDetail,
  RunKind,
  RunListing,
} from './eval-history';
import { availableMetrics, buildSeries, defaultMetrics } from './metric-series';

export const LOAD_ERROR_MESSAGE =
  'No se pudo cargar la evaluación. Se muestra el último estado conocido.';
export const DETAIL_ERROR_MESSAGE = 'No se pudo cargar la ejecución.';

export const RUN_ID = /^[A-Za-z0-9._-]{1,32}$/;

/**
 * State of the admin-eval screen: the list of runs of a kind with the metrics, the filters and the range, and the
 * run that is open with the cases that fail. It reads when the screen opens and when the viewer asks (no polling: a
 * run is saved whole and never changes, so a run that was read is kept). Provided by the screen, so its requests die
 * with it.
 */
@Injectable()
export class EvalStore {
  private readonly client = inject(EvalApiClient);

  readonly kind = signal<RunKind>(DEFAULT_KIND);
  readonly originFilter = signal<string | null>(null);
  readonly limit = signal(DEFAULT_LIMIT);
  readonly listing = signal<RunListing | null>(null);
  readonly loading = signal(false);
  /** True once a list has been read; until then an empty list means "not known yet", not "no runs". */
  readonly loaded = signal(false);
  readonly loadError = signal<string | null>(null);

  /** The metrics the viewer chose; `null` while the default ones of the kind apply. */
  private readonly chosenMetrics = signal<string[] | null>(null);

  readonly runs = computed(() => this.listing()?.runs ?? []);
  /** The runs that match the filters, shown or not. */
  readonly total = computed(() => this.listing()?.total ?? 0);
  readonly origins = computed(() => this.listing()?.origins ?? []);
  readonly availableMetrics = computed(() => availableMetrics(this.runs()));
  readonly selectedMetrics = computed(
    () => this.chosenMetrics() ?? defaultMetrics(this.kind(), this.runs()),
  );
  /** What the chart draws; without runs there is nothing to draw, not a series of no points. */
  readonly series = computed(() =>
    this.runs().length === 0 ? [] : buildSeries(this.runs(), this.selectedMetrics()),
  );

  /** The runs of the kind that the origin filter leaves out. */
  readonly hiddenByOrigin = computed(() => {
    const listing = this.listing();
    return listing ? listing.totalAllOrigins - listing.total : 0;
  });
  /** The runs that match the filters but are older than the range shown. */
  readonly notShownByRange = computed(() => {
    const listing = this.listing();
    return listing ? listing.total - listing.runs.length : 0;
  });
  readonly canShowMore = computed(
    () => this.notShownByRange() > 0 && this.limit() < LIMIT_STEPS[LIMIT_STEPS.length - 1],
  );
  /** No run of this kind has ever been saved. */
  readonly isEmpty = computed(() => this.loaded() && (this.listing()?.totalAllOrigins ?? 0) === 0);
  /** There are runs of the kind, but the origin filter hides all of them. */
  readonly isEmptyByFilter = computed(() => {
    const listing = this.listing();
    return this.loaded() && listing !== null && listing.totalAllOrigins > 0 && listing.total === 0;
  });

  // The run that is open.
  readonly selectedRunId = signal<string | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);
  /** The run asked for (e.g. from an old link) does not exist. */
  readonly runNotFound = signal(false);
  private readonly cache = signal<Record<string, RunDetail>>({});
  readonly detail = computed<RunDetail | null>(() => {
    const id = this.selectedRunId();
    return id === null ? null : (this.cache()[id] ?? null);
  });

  // What makes a case fail: the threshold in quality, the k in retrieval; informative, it blocks nothing.
  readonly threshold = signal(DEFAULT_THRESHOLD);
  private readonly chosenK = signal<number | null>(null);
  readonly categoryFilter = signal<string | null>(null);
  readonly metricFilter = signal<string | null>(null);
  readonly sort = signal<FailureSort>('worst');

  readonly availableKs = computed(() =>
    [...(this.detail()?.run.retrievalConfig.ks ?? [])].sort((a, b) => a - b),
  );
  /** The k of the criterion in retrieval: the chosen one, or the largest the run asked for. */
  readonly k = computed(() => {
    const detail = this.detail();
    return detail === null ? null : (this.chosenK() ?? maxK(detail.run.retrievalConfig));
  });
  readonly failureCriterion = computed<FailureCriterion | null>(() => {
    const detail = this.detail();
    if (detail === null) {
      return null;
    }
    if (detail.run.kind === 'QUALITY') {
      return { kind: 'QUALITY', threshold: this.threshold() };
    }
    const k = this.k();
    return k === null ? null : { kind: 'RETRIEVAL', k };
  });
  readonly failures = computed(() => {
    const detail = this.detail();
    const criterion = this.failureCriterion();
    return detail === null || criterion === null
      ? []
      : selectFailures(
          detail.results,
          criterion,
          { category: this.categoryFilter(), metric: this.metricFilter() },
          this.sort(),
        );
  });
  /** How many cases fail, whatever the filters. */
  readonly failureTotal = computed(() => {
    const detail = this.detail();
    const criterion = this.failureCriterion();
    return detail === null || criterion === null ? 0 : failureCount(detail.results, criterion);
  });
  readonly failureSummary = computed(() => {
    const detail = this.detail();
    return detail === null ? '' : summarizeFailures(this.failureTotal(), detail.run.casesMeasured);
  });

  private listSubscription?: Subscription;
  private detailSubscription?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.listSubscription?.unsubscribe();
      this.detailSubscription?.unsubscribe();
    });
  }

  /** Reads the runs of the current kind, origin and range; on failure the last known list stays. */
  loadListing(): void {
    this.listSubscription?.unsubscribe();
    this.loading.set(true);
    this.listSubscription = this.client
      .listRuns(this.kind(), this.originFilter(), this.limit())
      .subscribe({
        next: (listing) => {
          this.listing.set(listing);
          this.loaded.set(true);
          this.loadError.set(null);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set(LOAD_ERROR_MESSAGE);
          this.loading.set(false);
        },
      });
  }

  reload(): void {
    this.loadListing();
  }

  /** Sets the kind the screen opens with (from the address) without reading anything. */
  useKind(kind: RunKind): void {
    this.kind.set(kind);
  }

  /** Changes the kind: the series of the other kind are cleared, never mixed, and the list is read again. */
  setKind(kind: RunKind): void {
    if (kind === this.kind()) {
      return;
    }
    this.kind.set(kind);
    this.chosenMetrics.set(null);
    this.originFilter.set(null);
    this.limit.set(DEFAULT_LIMIT);
    this.listing.set(null);
    this.loaded.set(false);
    this.clearSelection();
    this.loadListing();
  }

  setOrigin(origin: string | null): void {
    this.originFilter.set(origin);
    this.loadListing();
  }

  /** Widens the range to the next step; the listing says how many runs there are beyond it. */
  showMore(): void {
    const next = LIMIT_STEPS.find((step) => step > this.limit());
    if (next === undefined) {
      return;
    }
    this.limit.set(next);
    this.loadListing();
  }

  /** Adds or removes a metric of the chart: at least one stays, and no more than the maximum at once. */
  toggleMetric(metric: string): void {
    const current = [...this.selectedMetrics()];
    const index = current.indexOf(metric);
    if (index >= 0) {
      if (current.length === 1) {
        return;
      }
      current.splice(index, 1);
    } else {
      if (current.length >= MAX_SELECTED_METRICS) {
        return;
      }
      current.push(metric);
    }
    this.chosenMetrics.set(current);
  }

  /** Opens a run. One that was read is not read again: a saved run never changes. */
  selectRun(runId: string): void {
    if (!RUN_ID.test(runId)) {
      return;
    }
    this.selectedRunId.set(runId);
    this.runNotFound.set(false);
    this.detailError.set(null);
    this.chosenK.set(null);
    this.categoryFilter.set(null);
    this.metricFilter.set(null);
    this.sort.set('worst');
    if (this.cache()[runId]) {
      this.detailSubscription?.unsubscribe();
      this.detailLoading.set(false);
      return;
    }
    this.loadDetail(runId);
  }

  retryDetail(): void {
    const runId = this.selectedRunId();
    if (runId !== null) {
      this.detailError.set(null);
      this.loadDetail(runId);
    }
  }

  clearSelection(): void {
    this.detailSubscription?.unsubscribe();
    this.selectedRunId.set(null);
    this.detailLoading.set(false);
    this.detailError.set(null);
  }

  /** The threshold of the quality criterion, kept between 0 and 1 with two decimals. */
  setThreshold(value: number): void {
    if (Number.isNaN(value)) {
      this.threshold.set(DEFAULT_THRESHOLD);
      return;
    }
    this.threshold.set(Math.round(Math.min(1, Math.max(0, value)) * 100) / 100);
  }

  setK(k: number): void {
    this.chosenK.set(k);
  }

  setCategoryFilter(category: string | null): void {
    this.categoryFilter.set(category);
  }

  setMetricFilter(metric: string | null): void {
    this.metricFilter.set(metric);
  }

  setSort(sort: FailureSort): void {
    this.sort.set(sort);
  }

  private loadDetail(runId: string): void {
    this.detailSubscription?.unsubscribe();
    this.detailLoading.set(true);
    this.detailSubscription = this.client.getRun(runId).subscribe({
      next: (detail) => {
        this.cache.update((cache) => ({ ...cache, [runId]: detail }));
        this.detailLoading.set(false);
      },
      error: (error: unknown) => {
        this.detailLoading.set(false);
        if ((error as Partial<ApiError>).status === 404) {
          this.runNotFound.set(true);
          this.selectedRunId.set(null);
        } else {
          this.detailError.set(DETAIL_ERROR_MESSAGE);
        }
      },
    });
  }
}
