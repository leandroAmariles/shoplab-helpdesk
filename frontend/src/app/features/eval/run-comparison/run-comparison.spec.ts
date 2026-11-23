import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EVAL_HISTORY_URL } from '../eval-api.client';
import { EvalStore } from '../eval-store';
import { COMPARISON_BODY } from '../eval-test-data';
import { RunComparisonView } from './run-comparison';

function listing() {
  return {
    kind: 'QUALITY',
    shown: 2,
    total: 2,
    total_all_origins: 2,
    origins: [{ origin: 'dev', count: 2 }],
    runs: ['t031-1', 't031-2'].map((id, index) => ({
      run_id: id,
      kind: 'QUALITY',
      started_at: `2026-11-0${index + 1}T10:00:00Z`,
      finished_at: `2026-11-0${index + 1}T10:10:00Z`,
      origin: 'dev',
      commit_sha: null,
      assistant_model: null,
      prompts: [],
      cases_measured: 96,
      metrics: { faithfulness: { mean: 0.7, n: 96 } },
    })),
  };
}

describe('RunComparisonView', () => {
  let fixture: ComponentFixture<RunComparisonView>;
  let root: HTMLElement;
  let store: EvalStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [EvalStore, provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(EvalStore);
    http = TestBed.inject(HttpTestingController);
    store.loadListing();
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`).flush(listing());
    fixture = TestBed.createComponent(RunComparisonView);
    root = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  function open(body: object = COMPARISON_BODY): void {
    store.compareWith('t031-2');
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`).flush(body);
    fixture.detectChanges();
  }

  function text(selector: string): string {
    return (root.querySelector(selector)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  }

  it('shows nothing until a comparison is asked for', () => {
    fixture.detectChanges();

    expect(root.querySelector('section')).toBeNull();
  });

  it('says that the comparison is loading', () => {
    store.compareWith('t031-2');
    fixture.detectChanges();

    expect(root.querySelector('.comparison-loading')?.getAttribute('role')).toBe('status');
    expect(text('.comparison-loading')).toContain('Comparando');
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`).flush(COMPARISON_BODY);
  });

  it('warns, with the fields that differ, that the difference may not be a change of quality', () => {
    open();

    const warning = root.querySelector('.warnings') as HTMLElement;
    expect(warning.getAttribute('role')).toBe('note');
    expect(warning.textContent).toContain('Puede no reflejar un cambio de calidad');
    expect(warning.textContent).toContain('judge.temperature');
    expect(warning.textContent).not.toContain('prompts.chat-system.version');
  });

  it('shows no warning when nothing that changes what is measured differs', () => {
    open({ ...COMPARISON_BODY, warnings: [] });

    expect(root.querySelector('.warnings')).toBeNull();
  });

  it('lists the parameters with the value of each side, unknown ones as «desconocido» and the harmless as informative', () => {
    open();

    const rows = Array.from(root.querySelectorAll('table.parameters tbody tr')).map((row) =>
      (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    expect(rows[0]).toContain('prompts.chat-system.version');
    expect(rows[0]).toContain('3');
    expect(rows[0]).toContain('4');
    expect(rows[0]).toContain('Informativo');
    expect(rows[1]).toContain('judge.temperature');
    expect(rows[1]).not.toContain('Informativo');
    expect(rows[2]).toContain('desconocido');
  });

  it('shows the difference of each metric and «sin valor» where a mean does not exist, never zero', () => {
    open();

    const rows = Array.from(root.querySelectorAll('table.metrics tbody tr'));
    expect(rows[0].textContent).toContain('0,7134');
    expect(rows[0].textContent).toContain('0,6800');
    expect(rows[0].textContent).toContain('−0,0334');
    expect(rows[1].textContent).toContain('sin valor');
    expect(rows[1].textContent).not.toContain('0,0000');
  });

  it('lists the cases that got worse, those that got better and those that cannot be compared', () => {
    open();

    expect(text('.worse')).toContain('Empeoraron (1)');
    expect(text('.worse')).toContain('G044');
    expect(text('.worse')).toContain('0,8000');
    expect(text('.worse')).toContain('0,2000');
    expect(text('.worse')).toContain('−0,6000');
    expect(text('.better')).toContain('Mejoraron (1)');
    expect(text('.better')).toContain('G010');
    expect(text('.better')).toContain('+0,5000');
    expect(text('.not-comparable')).toContain('No comparables (3)');
  });

  it('says why a case cannot be compared and claims no value for it', () => {
    open();

    const items = Array.from(root.querySelectorAll('.not-comparable li')).map((item) =>
      (item.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    expect(items.find((item) => item.includes('G030'))).toContain('el caso cambió');
    expect(items.find((item) => item.includes('G031'))).toContain('solo está en una ejecución');
    expect(items.find((item) => item.includes('G032'))).toContain('solo está en una ejecución');
    expect(items.join(' ')).not.toMatch(/\d,\d{4}/);
  });

  it('gives the cases that did not change or have no value as a figure only', () => {
    open();

    expect(text('.others')).toContain('Sin cambio: 0');
    expect(text('.others')).toContain('Sin valor: 1');
    expect(root.textContent).not.toContain('G020');
  });

  it('says that no case got worse or better when none did', () => {
    open({
      ...COMPARISON_BODY,
      counts: { ...COMPARISON_BODY.counts, worse: 0, better: 0 },
      cases: COMPARISON_BODY.cases.filter((c) => c.status !== 'WORSE' && c.status !== 'BETTER'),
    });

    expect(text('.worse')).toContain('Ningún caso empeoró');
    expect(text('.better')).toContain('Ningún caso mejoró');
  });

  it('changes the metric of the comparison and asks for it again', () => {
    open();

    const select = root.querySelector('select#comparison-metric') as HTMLSelectElement;
    select.value = 'context_precision';
    select.dispatchEvent(new Event('change'));

    const request = http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`);
    expect(request.request.params.get('metric')).toBe('context_precision');
    request.flush({ ...COMPARISON_BODY, metric: 'context_precision' });
  });

  it('changes the tolerance, kept between 0 and 1, and asks for it again', () => {
    open();

    const input = root.querySelector('input#comparison-drop') as HTMLInputElement;
    input.value = '0.1';
    input.dispatchEvent(new Event('input'));

    const request = http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`);
    expect(request.request.params.get('drop')).toBe('0.1');
    expect(input.getAttribute('min')).toBe('0');
    expect(input.getAttribute('max')).toBe('1');
    request.flush({ ...COMPARISON_BODY, drop: 0.1 });
  });

  it('shows the error in its own place with a retry and keeps nothing else', () => {
    store.compareWith('t031-2');
    http
      .expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`)
      .flush(null, { status: 0, statusText: 'Network' });
    fixture.detectChanges();

    const alert = root.querySelector('.comparison-error') as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('No se pudo comparar las ejecuciones');
    expect(root.querySelector('table.metrics')).toBeNull();

    (alert.querySelector('button') as HTMLButtonElement).click();
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`).flush(COMPARISON_BODY);
    fixture.detectChanges();

    expect(root.querySelector('.comparison-error')).toBeNull();
    expect(root.querySelector('table.metrics')).not.toBeNull();
  });

  it('closes the comparison', () => {
    open();

    (root.querySelector('button.close') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(root.querySelector('section')).toBeNull();
    expect(store.comparison()).toBeNull();
  });

  it('keeps its tables inside regions with an accessible name that can be reached with the keyboard', () => {
    open();

    const regions = Array.from(root.querySelectorAll('[role="region"]'));
    expect(regions.length).toBeGreaterThanOrEqual(2);
    for (const region of regions) {
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.getAttribute('aria-label')).toBeTruthy();
    }
  });
});
