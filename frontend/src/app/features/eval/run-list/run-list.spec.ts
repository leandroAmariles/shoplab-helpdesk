import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RunListItem } from '../eval-history';
import { RunList } from './run-list';

function run(
  runId: string,
  faithfulness: number | null,
  extra: Partial<RunListItem> = {},
): RunListItem {
  return {
    runId,
    kind: 'QUALITY',
    startedAt: '2026-11-21T14:02:11Z',
    finishedAt: '2026-11-21T14:11:03Z',
    origin: 'dev',
    commitSha: null,
    assistantModel: null,
    prompts: [],
    casesMeasured: 96,
    metrics: {
      faithfulness: { mean: faithfulness, n: faithfulness === null ? 0 : 96 },
      answer_relevancy: { mean: 0.8123, n: 70 },
    },
    ...extra,
  };
}

describe('RunList', () => {
  let fixture: ComponentFixture<RunList>;
  let root: HTMLElement;

  function render(
    runs: RunListItem[],
    metrics = ['faithfulness'],
    selected: string | null = null,
  ): void {
    fixture.componentRef.setInput('runs', runs);
    fixture.componentRef.setInput('metrics', metrics);
    fixture.componentRef.setInput('selectedRunId', selected);
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(RunList);
    root = fixture.nativeElement as HTMLElement;
  });

  it('lists a row for each run in the order it was given, as the text equivalent of the chart', () => {
    render([run('r1', 0.71), run('r2', 0.73)]);

    const rows = Array.from(root.querySelectorAll('tbody tr'));
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('r1');
    expect(rows[1].textContent).toContain('r2');
  });

  it('is inside a scrollable region with an accessible name, so the page never scrolls sideways', () => {
    render([run('r1', 0.71)]);

    const region = root.querySelector('[role="region"]') as HTMLElement;
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(region.getAttribute('aria-label')).toContain('Ejecuciones');
    expect(root.querySelector('caption')?.textContent).toContain('Ejecuciones');
  });

  it('has a column for each chosen metric with the value to four decimals and the cases', () => {
    render([run('r1', 0.71234)], ['faithfulness', 'answer_relevancy']);

    const headers = Array.from(root.querySelectorAll('thead th')).map((th) =>
      th.textContent?.trim(),
    );
    expect(headers).toContain('Fidelidad');
    expect(headers).toContain('Relevancia de la respuesta');
    const text = root.querySelector('tbody tr')?.textContent ?? '';
    expect(text).toContain('0,7123');
    expect(text).toContain('96 casos');
    expect(text).toContain('0,8123');
    expect(text).toContain('70 casos');
  });

  it('writes «sin valor» where a metric has no value and never a zero', () => {
    render([run('r1', null)]);

    const text = root.querySelector('tbody tr')?.textContent ?? '';
    expect(text).toContain('sin valor');
    expect(text).not.toContain('0,0000');
  });

  it('writes a real zero as a zero', () => {
    render([run('r1', 0)]);

    expect(root.querySelector('tbody tr')?.textContent).toContain('0,0000');
  });

  it('writes «sin valor» when the run does not have the metric', () => {
    render([run('r1', 0.5, { metrics: {} })]);

    expect(root.querySelector('tbody tr')?.textContent).toContain('sin valor');
  });

  it('shows the model of the assistant, or «desconocido» when it is not known', () => {
    render([run('r1', 0.5, { assistantModel: 'chat' }), run('r2', 0.5)]);

    const rows = Array.from(root.querySelectorAll('tbody tr'));
    expect(rows[0].textContent).toContain('chat');
    expect(rows[1].textContent).toContain('desconocido');
  });

  it('shows the origin of each run', () => {
    render([run('r1', 0.5, { origin: 'local' })]);

    expect(root.querySelector('tbody tr')?.textContent).toContain('local');
  });

  it('emits the run of the row when its button is pressed', () => {
    render([run('r1', 0.5), run('r2', 0.6)]);
    const chosen: string[] = [];
    fixture.componentInstance.runSelected.subscribe((id) => chosen.push(id));

    (root.querySelectorAll('tbody tr button')[1] as HTMLButtonElement).click();

    expect(chosen).toEqual(['r2']);
  });

  it('names the run in the button so that a screen reader can tell the rows apart', () => {
    render([run('r1', 0.5)]);

    const button = root.querySelector('tbody tr button') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toContain('r1');
  });

  it('marks the chosen run with text and aria-current, not only with colour', () => {
    render([run('r1', 0.5), run('r2', 0.6)], ['faithfulness'], 'r2');

    const rows = Array.from(root.querySelectorAll('tbody tr'));
    expect(rows[1].getAttribute('aria-current')).toBe('true');
    expect(rows[1].textContent).toContain('Elegida');
    expect(rows[0].hasAttribute('aria-current')).toBe(false);
    expect(rows[0].textContent).not.toContain('Elegida');
  });
});
