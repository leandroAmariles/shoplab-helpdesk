import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { formatDateTime } from '../eval-format';
import { metricLabel, MetricStat, RunListItem } from '../eval-history';
import { formatValue } from '../metric-series';

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
  readonly runSelected = output<string>();

  protected readonly metricLabel = metricLabel;
  protected readonly formatDateTime = formatDateTime;

  protected value(run: RunListItem, metric: string): string {
    const stat: MetricStat | undefined = run.metrics[metric];
    return formatValue(stat?.mean ?? null);
  }

  protected cases(run: RunListItem, metric: string): string | null {
    const stat: MetricStat | undefined = run.metrics[metric];
    return stat && stat.mean !== null ? `${stat.n} casos` : null;
  }
}
