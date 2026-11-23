import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RunListItem } from '../eval-history';
import { buildSeries, Series } from '../metric-series';
import { MetricChart } from './metric-chart';

function run(
  runId: string,
  startedAt: string,
  faithfulness: number | null,
  relevancy?: number | null,
): RunListItem {
  const metrics: RunListItem['metrics'] = {
    faithfulness: { mean: faithfulness, n: faithfulness === null ? 0 : 96 },
  };
  if (relevancy !== undefined) {
    metrics['answer_relevancy'] = { mean: relevancy, n: relevancy === null ? 0 : 80 };
  }
  return {
    runId,
    kind: 'QUALITY',
    startedAt,
    finishedAt: startedAt,
    origin: 'dev',
    commitSha: null,
    assistantModel: null,
    prompts: [],
    casesMeasured: 96,
    metrics,
  };
}

const RUNS = [
  run('r1', '2026-11-01T10:00:00Z', 0.69, 0.8),
  run('r2', '2026-11-02T10:00:00Z', null, 0.82),
  run('r3', '2026-11-03T10:00:00Z', 0.74, 0.7),
  run('r4', '2026-11-04T10:00:00Z', 0.78, null),
];

describe('MetricChart', () => {
  let fixture: ComponentFixture<MetricChart>;
  let root: HTMLElement;

  function render(series: Series[], selectedRunId: string | null = null): void {
    fixture.componentRef.setInput('series', series);
    fixture.componentRef.setInput('selectedRunId', selectedRunId);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(MetricChart);
    root = fixture.nativeElement as HTMLElement;
  });

  it('draws an image with a title and a description of the series', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    const svg = root.querySelector('svg.plot') as SVGElement;
    expect(svg.getAttribute('role')).toBe('img');
    const title = svg.querySelector('title') as Element;
    const description = svg.querySelector('desc') as Element;
    expect(svg.getAttribute('aria-labelledby')).toBe(`${title.id} ${description.id}`);
    expect(title.textContent).toContain('Evolución');
    expect(description.textContent).toContain('Fidelidad: 4 ejecuciones, de 0,6900 a 0,7800');
  });

  it('draws one marker for each run that has a value, and none where the value is missing', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    expect(root.querySelectorAll('g.point')).toHaveLength(3);
    expect(root.querySelectorAll('.gap-mark')).toHaveLength(1);
  });

  it('never draws a line through a gap and never puts a missing value on the zero line', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    // r1 | gap | r3 r4: one segment of two points, r1 alone has no line.
    const lines = root.querySelectorAll('polyline');
    expect(lines).toHaveLength(1);
    expect((lines[0].getAttribute('points') ?? '').trim().split(' ')).toHaveLength(2);
    // The mark of a gap is below the axis, not at the height of the zero.
    const zeroY = Number(root.querySelector('line.axis-x')?.getAttribute('y1'));
    const gap = root.querySelector('.gap-mark') as SVGElement;
    expect(Number(gap.getAttribute('y'))).toBeGreaterThan(zeroY);
    for (const point of Array.from(root.querySelectorAll('g.point'))) {
      expect(point.getAttribute('data-value')).not.toBe('0');
    }
  });

  it('draws a real zero as a point on the zero line', () => {
    render(
      buildSeries(
        [run('r1', '2026-11-01T10:00:00Z', 0), run('r2', '2026-11-02T10:00:00Z', 0.2)],
        ['faithfulness'],
      ),
    );

    const points = Array.from(root.querySelectorAll('g.point'));
    expect(points).toHaveLength(2);
    expect(points[0].getAttribute('data-value')).toBe('0');
    expect(root.querySelectorAll('.gap-mark')).toHaveLength(0);
  });

  it('shows a run alone between two gaps as a marker without a line', () => {
    render(
      buildSeries(
        [
          run('r1', '2026-11-01T10:00:00Z', null),
          run('r2', '2026-11-02T10:00:00Z', 0.5),
          run('r3', '2026-11-03T10:00:00Z', null),
        ],
        ['faithfulness'],
      ),
    );

    expect(root.querySelectorAll('g.point')).toHaveLength(1);
    expect(root.querySelectorAll('polyline')).toHaveLength(0);
    expect(root.querySelectorAll('.gap-mark')).toHaveLength(2);
  });

  it('tells each series apart by marker and stroke, not only by colour', () => {
    render(buildSeries(RUNS, ['faithfulness', 'answer_relevancy']));

    const first = root.querySelector('g.point[data-series="faithfulness"] path') as SVGPathElement;
    const second = root.querySelector(
      'g.point[data-series="answer_relevancy"] path',
    ) as SVGPathElement;
    expect(first.getAttribute('d')).not.toBe(second.getAttribute('d'));
    const dashes = Array.from(root.querySelectorAll('polyline')).map((line) =>
      line.getAttribute('stroke-dasharray'),
    );
    expect(new Set(dashes).size).toBeGreaterThan(1);
    expect(root.querySelectorAll('.legend li')).toHaveLength(2);
  });

  it('makes each point focusable with a label that says run, date, value and cases', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    const point = root.querySelector('g.point') as SVGGElement;
    expect(point.getAttribute('tabindex')).toBe('0');
    expect(point.getAttribute('role')).toBe('button');
    const label = point.getAttribute('aria-label') ?? '';
    expect(label).toContain('Fidelidad');
    expect(label).toContain('r1');
    expect(label).toContain('0,6900');
    expect(label).toContain('96 casos');
  });

  it('shows the run, the date, the exact value and the cases when a point gets the focus', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    expect(root.querySelector('.detail')?.textContent).toContain('Elige un punto');
    (root.querySelector('g.point') as SVGGElement).dispatchEvent(new FocusEvent('focus'));
    fixture.detectChanges();

    const text = root.querySelector('.detail')?.textContent ?? '';
    expect(text).toContain('r1');
    expect(text).toContain('0,6900');
    expect(text).toContain('calculado con 96 casos');
  });

  it('shows the panel on hover and hides it when the pointer leaves', () => {
    render(buildSeries(RUNS, ['faithfulness']));
    const point = root.querySelector('g.point') as SVGGElement;

    point.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    expect(root.querySelector('.detail')?.textContent).toContain('r1');

    point.dispatchEvent(new MouseEvent('mouseleave'));
    fixture.detectChanges();
    expect(root.querySelector('.detail')?.textContent).toContain('Elige un punto');
  });

  it('emits the run when a point is activated with the click, Enter or Space', () => {
    render(buildSeries(RUNS, ['faithfulness']));
    const chosen: string[] = [];
    fixture.componentInstance.runSelected.subscribe((id) => chosen.push(id));
    const points = Array.from(root.querySelectorAll('g.point'));

    points[0].dispatchEvent(new MouseEvent('click'));
    points[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    points[2].dispatchEvent(new KeyboardEvent('keydown', { key: ' ' }));
    points[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));

    expect(chosen).toEqual(['r1', 'r3', 'r4']);
  });

  it('marks the chosen run with a vertical line and aria-current', () => {
    render(buildSeries(RUNS, ['faithfulness']), 'r3');

    expect(root.querySelectorAll('line.selected-run')).toHaveLength(1);
    expect(root.querySelector('g.point[data-run="r3"]')?.getAttribute('aria-current')).toBe('true');
    expect(root.querySelector('g.point[data-run="r1"]')?.hasAttribute('aria-current')).toBe(false);
  });

  it('asks for more than one run when there is only one', () => {
    render(buildSeries([RUNS[0]], ['faithfulness']));

    expect(root.querySelectorAll('g.point')).toHaveLength(1);
    expect(root.querySelector('.single-run')?.textContent).toContain(
      'Hace falta más de una ejecución',
    );
  });

  it('does not ask for more runs when there are several', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    expect(root.querySelector('.single-run')).toBeNull();
  });

  it('draws nothing without series', () => {
    render([]);

    expect(root.querySelector('svg.plot')).toBeNull();
  });

  it('labels the axis with the quarters and at most a few runs', () => {
    render(buildSeries(RUNS, ['faithfulness']));

    const labels = Array.from(root.querySelectorAll('.y-label')).map((label) =>
      label.textContent?.trim(),
    );
    expect(labels).toEqual(['0,00', '0,25', '0,50', '0,75', '1,00']);
    expect(root.querySelectorAll('.x-label').length).toBeLessThanOrEqual(6);
  });
});
