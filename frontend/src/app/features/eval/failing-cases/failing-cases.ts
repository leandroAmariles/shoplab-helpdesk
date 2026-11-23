import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FailingCase } from '../case-failures';
import { EvalStore } from '../eval-store';
import { metricLabel, StoredResult } from '../eval-history';
import { formatValue, orderMetrics } from '../metric-series';

/** A score of the case as the row shows it. */
interface ShownScore {
  metric: string;
  label: string;
  value: string;
  below: boolean;
}

/**
 * The cases of the open run that fail, with the threshold (quality) or the k (retrieval) the viewer chooses, the
 * filters and the order (specs/LAB-32). A case opens to show its reference answer and the documents expected and
 * retrieved. The text of the answer and of the fragments is never here: it lives in the trace of Langfuse.
 */
@Component({
  selector: 'app-failing-cases',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './failing-cases.html',
  styleUrl: './failing-cases.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FailingCases {
  protected readonly store = inject(EvalStore);

  private readonly open = signal<string | null>(null);
  protected readonly copied = signal<string | null>(null);

  protected readonly categories = computed(() =>
    [...new Set(this.store.detail()?.results.map((result) => result.category) ?? [])].sort(),
  );
  protected readonly metrics = computed(() =>
    orderMetrics(this.store.detail()?.results.flatMap((result) => Object.keys(result.scores)) ?? []),
  );
  protected readonly isQuality = computed(() => this.store.failureCriterion()?.kind === 'QUALITY');
  protected readonly isRetrieval = computed(() => this.store.failureCriterion()?.kind === 'RETRIEVAL');
  protected readonly metricLabel = metricLabel;

  protected isOpen(result: StoredResult): boolean {
    return this.open() === this.key(result);
  }

  protected toggle(result: StoredResult): void {
    this.open.update((current) => (current === this.key(result) ? null : this.key(result)));
  }

  /** What the row shows of the scores: all of them in quality, the hit at the chosen k in retrieval. */
  protected shownScores(failure: FailingCase): ShownScore[] {
    const criterion = this.store.failureCriterion();
    const result = failure.result;
    if (criterion?.kind === 'RETRIEVAL') {
      const metric = `hit_at_${criterion.k}`;
      return [this.shown(metric, result.scores[metric] ?? null, true)];
    }
    return orderMetrics(Object.keys(result.scores)).map((metric) =>
      this.shown(metric, result.scores[metric], failure.failing.includes(metric)),
    );
  }

  protected isExpectedFound(result: StoredResult, document: string): boolean {
    return result.retrievedDocuments.includes(document);
  }

  protected isRetrievedExpected(result: StoredResult, document: string): boolean {
    return result.expectedDocuments.includes(document);
  }

  protected onThreshold(event: Event): void {
    this.store.setThreshold((event.target as HTMLInputElement).valueAsNumber);
  }

  protected onK(event: Event): void {
    this.store.setK(Number((event.target as HTMLSelectElement).value));
  }

  protected onCategory(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.store.setCategoryFilter(value === '' ? null : value);
  }

  protected onMetric(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.store.setMetricFilter(value === '' ? null : value);
  }

  protected onSort(event: Event): void {
    this.store.setSort((event.target as HTMLSelectElement).value as 'worst' | 'caseId' | 'category');
  }

  protected copy(traceId: string): void {
    void navigator.clipboard?.writeText(traceId).then(
      () => this.copied.set(traceId),
      () => this.copied.set(null),
    );
  }

  private key(result: StoredResult): string {
    return `${this.store.selectedRunId()}/${result.caseId}`;
  }

  private shown(metric: string, value: number | null, below: boolean): ShownScore {
    return {
      metric,
      label: metricLabel(metric),
      value: formatValue(value),
      below: below && value !== null,
    };
  }
}
