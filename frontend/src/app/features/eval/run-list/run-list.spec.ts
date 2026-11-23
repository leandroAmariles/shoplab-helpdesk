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

    (root.querySelectorAll('tbody tr button.view-detail')[1] as HTMLButtonElement).click();

    expect(chosen).toEqual(['r2']);
  });

  it('names the run in the button so that a screen reader can tell the rows apart', () => {
    render([run('r1', 0.5)]);

    const button = root.querySelector('tbody tr button.view-detail') as HTMLButtonElement;
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

  describe('comparison actions', () => {
    function button(row: number, label: string): HTMLButtonElement {
      const rows = root.querySelectorAll('tbody tr');
      return Array.from(rows[row].querySelectorAll('button')).find((b) =>
        (b.textContent ?? '').includes(label),
      ) as HTMLButtonElement;
    }

    it('lets the viewer use a run as the reference and compare a run with it', () => {
      render([run('r1', 0.5), run('r2', 0.6)]);
      fixture.componentRef.setInput('availability', { r1: 'no-reference', r2: 'available' });
      fixture.detectChanges();
      const references: string[] = [];
      const compared: string[] = [];
      fixture.componentInstance.referenceChosen.subscribe((id) => references.push(id));
      fixture.componentInstance.compareRequested.subscribe((id) => compared.push(id));

      button(0, 'Usar como referencia').click();
      button(1, 'Comparar con la referencia').click();

      expect(references).toEqual(['r1']);
      expect(compared).toEqual(['r2']);
    });

    it('names the run in each action so that a screen reader can tell the rows apart', () => {
      render([run('r1', 0.5)]);

      expect(button(0, 'Usar como referencia').getAttribute('aria-label')).toContain('r1');
      expect(button(0, 'Comparar con la referencia').getAttribute('aria-label')).toContain('r1');
    });

    it('marks the reference with text', () => {
      render([run('r1', 0.5), run('r2', 0.6)]);
      fixture.componentRef.setInput('referenceRunId', 'r1');
      fixture.detectChanges();

      const rows = Array.from(root.querySelectorAll('tbody tr'));
      expect(rows[0].textContent).toContain('Referencia');
      expect(rows[1].textContent).not.toContain('Referencia');
    });

    it('disables the comparison with a run of another kind and says why', () => {
      render([run('r1', 0.5), run('r2', 0.6)]);
      fixture.componentRef.setInput('availability', { r1: 'available', r2: 'other-kind' });
      fixture.detectChanges();

      expect(button(1, 'Comparar con la referencia').disabled).toBe(true);
      expect(root.querySelectorAll('tbody tr')[1].textContent).toContain(
        'Sus métricas no son las mismas',
      );
      expect(button(0, 'Comparar con la referencia').disabled).toBe(false);
    });

    it('disables the comparison of a run that has no reference and of the reference itself', () => {
      render([run('r1', 0.5), run('r2', 0.6)]);
      fixture.componentRef.setInput('availability', { r1: 'no-reference', r2: 'is-reference' });
      fixture.detectChanges();

      expect(button(0, 'Comparar con la referencia').disabled).toBe(true);
      expect(root.querySelectorAll('tbody tr')[0].textContent).toContain('Elige una referencia');
      expect(button(1, 'Comparar con la referencia').disabled).toBe(true);
      expect(root.querySelectorAll('tbody tr')[1].textContent).toContain('Es la referencia');
    });

    it('explains a disabled action with text that the button points to', () => {
      render([run('r1', 0.5)]);
      fixture.componentRef.setInput('availability', { r1: 'other-kind' });
      fixture.detectChanges();

      const compare = button(0, 'Comparar con la referencia');
      const hint = root.querySelector(`#${compare.getAttribute('aria-describedby')}`);
      expect(hint?.textContent).toContain('Sus métricas no son las mismas');
    });
  });
});
