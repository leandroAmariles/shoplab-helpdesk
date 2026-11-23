import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router } from '@angular/router';
import { EvalStore } from './eval-store';
import { KIND_LABELS, metricLabel, RunKind } from './eval-history';
import { MetricChart } from './metric-chart/metric-chart';
import { RunList } from './run-list/run-list';

const KINDS: readonly RunKind[] = ['QUALITY', 'RETRIEVAL'];

function isRunKind(value: string | null): value is RunKind {
  return value !== null && (KINDS as readonly string[]).includes(value);
}

/** Evolution of the evaluation metrics of the assistant, run by run (specs/LAB-32). Read only. */
@Component({
  selector: 'app-admin-eval',
  imports: [MatButtonModule, MatButtonToggleModule, MatIconModule, MetricChart, RunList],
  providers: [EvalStore],
  templateUrl: './admin-eval.html',
  styleUrl: './admin-eval.scss',
})
export class AdminEval {
  protected readonly store = inject(EvalStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly kinds = KINDS;
  protected readonly kindLabels = KIND_LABELS;
  protected readonly metricLabel = metricLabel;

  constructor() {
    const kind = this.route.snapshot.queryParamMap.get('tipo');
    if (isRunKind(kind)) {
      this.store.useKind(kind);
    }
    this.store.loadListing();
  }

  protected onKindChange(kind: RunKind): void {
    this.store.setKind(kind);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tipo: kind },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected onOriginChange(origin: string): void {
    this.store.setOrigin(origin === '' ? null : origin);
  }

  protected isSelected(metric: string): boolean {
    return this.store.selectedMetrics().includes(metric);
  }
}
