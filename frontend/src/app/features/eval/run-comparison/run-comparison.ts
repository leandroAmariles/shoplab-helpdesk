import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { EvalStore } from '../eval-store';
import { CaseComparison, metricLabel, MetricStat } from '../eval-history';
import { formatDelta, formatValue } from '../metric-series';

const UNKNOWN = 'desconocido';

/**
 * Two runs of the same kind side by side (specs/LAB-32, US3): what was measured differently, the metrics of the whole
 * set and the cases that got worse, got better or cannot be compared. A difference that changes what is measured is
 * warned about, because it may not be a change of quality. The values come from the backend, which uses the rule of
 * the documented SQL queries; nothing is decided here.
 */
@Component({
  selector: 'app-run-comparison',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './run-comparison.html',
  styleUrl: './run-comparison.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunComparisonView {
  protected readonly store = inject(EvalStore);

  protected readonly metricLabel = metricLabel;
  protected readonly formatDelta = formatDelta;
  protected readonly unknown = UNKNOWN;

  protected readonly worse = computed(() => this.casesWith('WORSE'));
  protected readonly better = computed(() => this.casesWith('BETTER'));
  protected readonly notComparable = computed(() => [
    ...this.casesWith('NOT_COMPARABLE'),
    ...this.casesWith('ONLY_BASE'),
    ...this.casesWith('ONLY_TARGET'),
  ]);

  protected value(stat: MetricStat | null): string {
    return formatValue(stat?.mean ?? null);
  }

  /** Why a case says nothing about its values. */
  protected reason(item: CaseComparison): string {
    return item.status === 'NOT_COMPARABLE' ? 'el caso cambió' : 'solo está en una ejecución';
  }

  protected onMetric(event: Event): void {
    this.store.setComparisonMetric((event.target as HTMLSelectElement).value);
  }

  protected onDrop(event: Event): void {
    this.store.setComparisonDrop((event.target as HTMLInputElement).valueAsNumber);
  }

  private casesWith(status: CaseComparison['status']): CaseComparison[] {
    return (this.store.comparison()?.cases ?? []).filter((item) => item.status === status);
  }
}
