import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatDateTime } from '../eval-format';
import { CompareAvailability, metricLabel, MetricStat, RunListItem } from '../eval-history';
import { formatValue } from '../metric-series';

const HINTS: Partial<Record<CompareAvailability, string>> = {
  'other-kind': 'Sus métricas no son las mismas',
  'no-reference': 'Elige una referencia',
  'is-reference': 'Es la referencia',
};

/**
 * The runs of the chart as a table: the text equivalent of the chart (the same rows and values) and the list the
 * viewer chooses from. A value without value is written «sin valor», never as a zero.
 */
@Component({
  selector: 'app-run-list',
  imports: [MatButtonModule],
  templateUrl: './run-list.html',
  styleUrl: './run-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunList {
  readonly runs = input.required<RunListItem[]>();
  /** The metrics of the chart, one column each. */
  readonly metrics = input.required<string[]>();
  readonly selectedRunId = input<string | null>(null);
  /** The reference run of the comparison, marked in the list. */
  readonly referenceRunId = input<string | null>(null);
  /** What each run can do with the comparison; a run that is not here can be compared. */
  readonly availability = input<Record<string, CompareAvailability>>({});
  readonly runSelected = output<string>();
  readonly referenceChosen = output<string>();
  readonly compareRequested = output<string>();

  protected readonly metricLabel = metricLabel;
  protected readonly formatDateTime = formatDateTime;

  protected availabilityOf(run: RunListItem): CompareAvailability {
    return this.availability()[run.runId] ?? 'available';
  }

  protected hint(availability: CompareAvailability): string | null {
    return HINTS[availability] ?? null;
  }

  protected value(run: RunListItem, metric: string): string {
    const stat: MetricStat | undefined = run.metrics[metric];
    return formatValue(stat?.mean ?? null);
  }

  protected cases(run: RunListItem, metric: string): string | null {
    const stat: MetricStat | undefined = run.metrics[metric];
    return stat && stat.mean !== null ? `${stat.n} casos` : null;
  }
}
