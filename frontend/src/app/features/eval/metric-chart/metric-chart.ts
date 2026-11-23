import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { ChartBox, segments, X_TICKS_MAX, xFor, yFor, Y_TICKS } from '../chart-scale';
import { formatDate, formatShortDate } from '../eval-format';
import { describeSeries, formatValue, Series } from '../metric-series';

/** The plot area inside the `viewBox` of the SVG. */
const BOX: ChartBox = { width: 720, height: 320, left: 56, right: 24, top: 16, bottom: 56 };

/** A marker and a stroke for each series, so the series are not told apart by colour alone. */
const MARKERS = ['circle', 'square', 'diamond', 'triangle', 'cross'] as const;
const DASHES = ['', '8 4', '2 4', '10 4 2 4', '1 5'];
const R = 5;

let nextChartId = 0;

/** The outline of a marker centred at (x, y). */
export function markerPath(shape: string, x: number, y: number): string {
  switch (shape) {
    case 'square':
      return `M${x - R},${y - R}h${2 * R}v${2 * R}h${-2 * R}z`;
    case 'diamond':
      return `M${x},${y - R - 1}l${R + 1},${R + 1}l${-(R + 1)},${R + 1}l${-(R + 1)},${-(R + 1)}z`;
    case 'triangle':
      return `M${x},${y - R - 1}L${x + R + 1},${y + R}L${x - R - 1},${y + R}z`;
    case 'cross':
      return `M${x - R},${y - R}L${x + R},${y + R}M${x - R},${y + R}L${x + R},${y - R}`;
    default:
      return `M${x - R},${y}a${R},${R} 0 1,0 ${2 * R},0a${R},${R} 0 1,0 ${-2 * R},0`;
  }
}

interface PlottedPoint {
  runId: string;
  startedAt: string;
  value: number | null;
  n: number;
  x: number;
  y: number | null;
}

interface Plot {
  metric: string;
  label: string;
  index: number;
  shape: string;
  dash: string;
  points: PlottedPoint[];
  lines: string[];
}

/** What the panel under the chart shows about the point that has the focus or the pointer. */
interface ActivePoint {
  label: string;
  runId: string;
  date: string;
  value: string;
  cases: string;
}

/**
 * The evolution of the metrics, one point per run (specs/LAB-32). An SVG of its own: a run without value is a gap in
 * the line and a mark under the axis, never a zero. Each point can be reached with the keyboard.
 */
@Component({
  selector: 'app-metric-chart',
  templateUrl: './metric-chart.html',
  styleUrl: './metric-chart.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricChart {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly series = input.required<Series[]>();
  readonly selectedRunId = input<string | null>(null);
  readonly runSelected = output<string>();

  protected readonly box = BOX;
  protected readonly titleId = `metric-chart-title-${++nextChartId}`;
  protected readonly descriptionId = `metric-chart-desc-${nextChartId}`;
  protected readonly yTicks = Y_TICKS.map((value) => ({
    y: yFor(value, BOX),
    label: value.toFixed(2).replace('.', ','),
  }));
  protected readonly zeroY = yFor(0, BOX);
  protected readonly markerPath = markerPath;
  protected readonly active = signal<ActivePoint | null>(null);

  protected readonly plots = computed<Plot[]>(() =>
    this.series().map((series, index) => {
      const count = series.points.length;
      const points = series.points.map((point, position) => ({
        ...point,
        x: xFor(position, count, BOX),
        y: point.value === null ? null : yFor(point.value, BOX),
      }));
      return {
        metric: series.metric,
        label: series.label,
        index,
        shape: MARKERS[index % MARKERS.length],
        dash: DASHES[index % DASHES.length],
        points,
        lines: segments(points)
          .filter((part) => part.length > 1)
          .map((part) => part.map((point) => `${point.x},${point.y}`).join(' ')),
      };
    }),
  );

  protected readonly description = computed(() => this.series().map(describeSeries).join('. '));
  protected readonly runCount = computed(() => this.series()[0]?.points.length ?? 0);

  /** The runs that get a label on the horizontal axis: all of them when they are few, evenly spread otherwise. */
  protected readonly xLabels = computed(() => {
    const points = this.plots()[0]?.points ?? [];
    if (points.length <= X_TICKS_MAX) {
      return points.map((point) => ({ x: point.x, text: formatShortDate(point.startedAt) }));
    }
    const picked = new Set<number>();
    for (let tick = 0; tick < X_TICKS_MAX; tick++) {
      picked.add(Math.round((tick * (points.length - 1)) / (X_TICKS_MAX - 1)));
    }
    return [...picked].map((index) => ({
      x: points[index].x,
      text: formatShortDate(points[index].startedAt),
    }));
  });

  protected readonly selectedX = computed(() => {
    const id = this.selectedRunId();
    const point = id === null ? undefined : this.plots()[0]?.points.find((p) => p.runId === id);
    return point?.x ?? null;
  });

  /** Where the mark of a gap goes: under the axis, spread by series so that two gaps do not overlap. */
  protected gapX(plot: Plot, point: PlottedPoint): number {
    return point.x + (plot.index - (this.plots().length - 1) / 2) * 12;
  }

  protected pointLabel(plot: Plot, point: PlottedPoint): string {
    return (
      `${plot.label}, ejecución ${point.runId}, ${formatDate(point.startedAt)}, ` +
      `valor ${formatValue(point.value)}, calculado con ${point.n} casos`
    );
  }

  protected activate(plot: Plot, point: PlottedPoint): void {
    this.active.set({
      label: plot.label,
      runId: point.runId,
      date: formatDate(point.startedAt),
      value: formatValue(point.value),
      cases: `calculado con ${point.n} casos`,
    });
  }

  protected deactivate(): void {
    this.active.set(null);
  }

  protected select(runId: string): void {
    this.runSelected.emit(runId);
  }

  /** A click or a tap shows the panel too: on a touch screen there is no hover or focus ring to show it. */
  protected onClick(plot: Plot, point: PlottedPoint): void {
    this.activate(plot, point);
    this.select(point.runId);
  }

  /**
   * Enter and Space open the run; the arrows move the focus along the points of the same series (a gap has no point to
   * reach, and the first and the last stay where they are); Escape closes the panel.
   */
  protected onKeydown(event: KeyboardEvent, plot: Plot, point: PlottedPoint): void {
    switch (event.key) {
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.select(point.runId);
        break;
      case 'ArrowRight':
        event.preventDefault();
        this.focusNeighbour(plot, point, 1);
        break;
      case 'ArrowLeft':
        event.preventDefault();
        this.focusNeighbour(plot, point, -1);
        break;
      case 'Escape':
        this.deactivate();
        break;
      default:
        break;
    }
  }

  private focusNeighbour(plot: Plot, point: PlottedPoint, step: number): void {
    const reachable = plot.points.filter((candidate) => candidate.y !== null);
    const next = reachable[reachable.indexOf(point) + step];
    if (next === undefined) {
      return;
    }
    const element = this.host.nativeElement.querySelector<SVGGElement>(
      `g.point[data-series="${plot.metric}"][data-run="${next.runId}"]`,
    );
    element?.focus();
  }
}
