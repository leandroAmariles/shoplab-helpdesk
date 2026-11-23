import { Component, effect, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router } from '@angular/router';
import { EvalStore, RUN_ID } from './eval-store';
import { FailingCases } from './failing-cases/failing-cases';
import { KIND_LABELS, metricLabel, RunKind } from './eval-history';
import { MetricChart } from './metric-chart/metric-chart';
import { RunDetailView } from './run-detail/run-detail';
import { RunList } from './run-list/run-list';

const KINDS: readonly RunKind[] = ['QUALITY', 'RETRIEVAL'];

function isRunKind(value: string | null): value is RunKind {
  return value !== null && (KINDS as readonly string[]).includes(value);
}

/** Evolution of the evaluation metrics of the assistant, run by run (specs/LAB-32). Read only. */
@Component({
  selector: 'app-admin-eval',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    FailingCases,
    MetricChart,
    RunDetailView,
    RunList,
  ],
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
    const runId = this.route.snapshot.queryParamMap.get('ejecucion');
    if (runId !== null && RUN_ID.test(runId)) {
      this.store.selectRun(runId);
    }
    this.store.loadListing();

    // A run that does not exist (an old link) leaves no trace in the address.
    effect(() => {
      if (this.store.runNotFound()) {
        this.writeAddress({ ejecucion: null });
      }
    });
  }

  protected onKindChange(kind: RunKind): void {
    this.store.setKind(kind);
    this.writeAddress({ tipo: kind, ejecucion: null });
  }

  /** Opens a run chosen in the list or in the chart and writes it in the address so the link can be shared. */
  protected onRunSelected(runId: string): void {
    this.store.selectRun(runId);
    this.writeAddress({ ejecucion: runId });
  }

  private writeAddress(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
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
