import { ChartBox, segments, X_TICKS_MAX, xFor, yFor, Y_TICKS } from './chart-scale';

const BOX: ChartBox = { width: 600, height: 300, left: 50, right: 20, top: 10, bottom: 40 };

describe('chart-scale', () => {
  describe('xFor', () => {
    it('spreads the runs evenly between the borders of the plot, in order', () => {
      expect(xFor(0, 5, BOX)).toBe(50);
      expect(xFor(4, 5, BOX)).toBe(580);
      expect(xFor(2, 5, BOX)).toBe(315);
    });

    it('centres a single run and does not divide by zero with none', () => {
      expect(xFor(0, 1, BOX)).toBe(315);
      expect(xFor(0, 0, BOX)).toBe(315);
      expect(Number.isFinite(xFor(0, 0, BOX))).toBe(true);
    });
  });

  describe('yFor', () => {
    it('puts 0 at the bottom of the plot and 1 at the top', () => {
      expect(yFor(0, BOX)).toBe(260);
      expect(yFor(1, BOX)).toBe(10);
      expect(yFor(0.5, BOX)).toBe(135);
    });

    it('keeps values outside 0 to 1 on the border of the plot', () => {
      expect(yFor(-0.3, BOX)).toBe(260);
      expect(yFor(1.4, BOX)).toBe(10);
    });
  });

  it('has the marks of the axis every quarter', () => {
    expect(Y_TICKS).toEqual([0, 0.25, 0.5, 0.75, 1]);
  });

  it('shows at most a few labels on the axis of the runs', () => {
    expect(X_TICKS_MAX).toBeGreaterThanOrEqual(3);
  });

  describe('segments', () => {
    const point = (value: number | null) => ({ value });

    it('splits a series at each gap and never joins the points around it', () => {
      const parts = segments([point(0.1), point(0.2), point(null), point(0.4), point(0.5)]);

      expect(parts.map((part) => part.map((p) => p.value))).toEqual([
        [0.1, 0.2],
        [0.4, 0.5],
      ]);
    });

    it('keeps a point between two gaps as a segment of one', () => {
      const parts = segments([point(null), point(0.3), point(null)]);

      expect(parts.map((part) => part.map((p) => p.value))).toEqual([[0.3]]);
    });

    it('treats a real zero as a value, not as a gap', () => {
      const parts = segments([point(0.2), point(0), point(0.2)]);

      expect(parts).toHaveLength(1);
      expect(parts[0]).toHaveLength(3);
    });

    it('has no segments without values', () => {
      expect(segments([])).toEqual([]);
      expect(segments([point(null), point(null)])).toEqual([]);
    });
  });
});
