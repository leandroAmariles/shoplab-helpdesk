import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { formatDateTime } from '../eval-format';
import { metricLabel, RunDetail, RunPrompt } from '../eval-history';
import { formatValue } from '../metric-series';

const UNKNOWN = 'desconocido';

/** One decimal that reads naturally in Spanish: `0,6`, `0`. */
function decimal(value: number): string {
  return String(value).replace('.', ',');
}

/**
 * What a run was measured with and its summary (specs/LAB-32): the prompts with their numbered version, the models,
 * the judge, the retrieval settings and the mean of each metric for the whole set and for each category. What is not
 * known is written «desconocido»; a mean without value is written «sin valor», never as a zero.
 */
@Component({
  selector: 'app-run-detail',
  templateUrl: './run-detail.html',
  styleUrl: './run-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunDetailView {
  readonly detail = input.required<RunDetail>();

  protected readonly metricLabel = metricLabel;
  protected readonly formatDateTime = formatDateTime;
  protected readonly unknown = UNKNOWN;

  protected readonly run = computed(() => this.detail().run);

  /** The metrics of the summary, in the order of the whole set. */
  protected readonly metrics = computed(() => {
    const names = new Set<string>();
    for (const scope of this.detail().summary) {
      Object.keys(scope.metrics).forEach((name) => names.add(name));
    }
    return [...names];
  });

  /** The cases of the whole set that have no value in each metric: the cases minus those that count in the mean. */
  protected readonly unscored = computed(() => {
    const all = this.detail().summary.find((scope) => scope.scope === 'all');
    if (!all) {
      return [];
    }
    return this.metrics().map((metric) => ({
      metric,
      count: all.cases - (all.metrics[metric]?.n ?? 0),
    }));
  });

  protected readonly commit = computed(() => this.run().commitSha?.slice(0, 7) ?? UNKNOWN);
  protected readonly dataset = computed(() => this.run().datasetHash.slice(0, 12));

  protected readonly judge = computed(() => {
    const judge = this.run().judge;
    if (judge === null) {
      return null;
    }
    const parts = [`${judge.model}${judge.underlyingModel ? ` (${judge.underlyingModel})` : ''}`];
    if (judge.temperature !== null) {
      parts.push(`temperatura ${decimal(judge.temperature)}`);
    }
    if (judge.seed !== null) {
      parts.push(`semilla ${judge.seed}`);
    }
    if (judge.maxInputChars !== null) {
      parts.push(`entrada máx. ${judge.maxInputChars} caracteres`);
    }
    return parts.join(' · ');
  });

  protected readonly evaluator = computed(() => {
    const evaluator = this.run().evaluator;
    if (evaluator === null) {
      return null;
    }
    const library = `${evaluator.library}${evaluator.libraryVersion ? ` ${evaluator.libraryVersion}` : ''}`;
    return evaluator.judgmentInstructionsVersion
      ? `${library} · instrucciones de juicio v${evaluator.judgmentInstructionsVersion}`
      : library;
  });

  protected readonly retrieval = computed(() => {
    const config = this.run().retrievalConfig;
    const parts = [
      `top-k ${config.topK}`,
      `umbral ${decimal(config.similarityThreshold)}`,
      `contexto ${config.maxContextCharacters} caracteres`,
    ];
    if (config.depth !== null) {
      parts.push(`profundidad ${config.depth}`);
    }
    if (config.ks !== null && config.ks.length > 0) {
      parts.push(`k: ${config.ks.join(', ')}`);
    }
    return parts.join(' · ');
  });

  protected promptLabel(prompt: RunPrompt): string {
    if (prompt.version === null) {
      return `${prompt.name} · texto por defecto`;
    }
    return `${prompt.name} v${prompt.version} · ${prompt.pinned ? 'fijada por petición' : 'producción'}`;
  }

  protected scopeLabel(scope: string): string {
    return scope === 'all' ? 'Conjunto completo' : scope;
  }

  protected value(mean: number | null): string {
    return formatValue(mean);
  }
}
