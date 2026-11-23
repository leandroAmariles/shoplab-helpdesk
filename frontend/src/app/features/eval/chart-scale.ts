/** The plot area of the chart inside its SVG, in the units of the `viewBox`. */
export interface ChartBox {
  width: number;
  height: number;
  left: number;
  right: number;
  top: number;
  bottom: number;
}

/** The marks of the vertical axis: the metrics go from 0 to 1. */
export const Y_TICKS: readonly number[] = [0, 0.25, 0.5, 0.75, 1];

/** The horizontal axis labels at most this many runs; the table below the chart lists all of them. */
export const X_TICKS_MAX = 6;

/** The horizontal position of the run `index` of `count`: evenly spread, a single run is centred. */
export function xFor(index: number, count: number, box: ChartBox): number {
  const inner = box.width - box.left - box.right;
  if (count <= 1) {
    return box.left + inner / 2;
  }
  return box.left + (index * inner) / (count - 1);
}

/** The vertical position of a value: 0 at the bottom of the plot, 1 at the top; others stay on the border. */
export function yFor(value: number, box: ChartBox): number {
  const inner = box.height - box.top - box.bottom;
  const clamped = Math.min(1, Math.max(0, value));
  return box.top + (1 - clamped) * inner;
}

/**
 * Splits a series into the runs of consecutive points that have a value: a gap ends a segment, so a line is never
 * drawn through a run without value. A point alone between two gaps is a segment of one.
 */
export function segments<T extends { value: number | null }>(points: readonly T[]): T[][] {
  const parts: T[][] = [];
  let current: T[] = [];
  for (const point of points) {
    if (point.value === null) {
      if (current.length > 0) {
        parts.push(current);
        current = [];
      }
    } else {
      current.push(point);
    }
  }
  if (current.length > 0) {
    parts.push(current);
  }
  return parts;
}
