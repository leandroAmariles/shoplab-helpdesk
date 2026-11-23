import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { AdminEval } from './admin-eval';
import { EVAL_HISTORY_URL } from './eval-api.client';
import { COMPARISON_BODY, RUN_BODY } from './eval-test-data';

function listing() {
  return {
    kind: 'QUALITY',
    shown: 3,
    total: 3,
    total_all_origins: 3,
    origins: [{ origin: 'dev', count: 3 }],
    runs: ['t031-1', 't031-2', 't031-3'].map((id, index) => ({
      run_id: id,
      kind: 'QUALITY',
      started_at: `2026-11-0${index + 1}T10:00:00Z`,
      finished_at: `2026-11-0${index + 1}T10:10:00Z`,
      origin: 'dev',
      commit_sha: null,
      assistant_model: null,
      prompts: [],
      cases_measured: 96,
      metrics: {
        faithfulness: { mean: 0.6 + index / 10, n: 96 },
        answer_relevancy: { mean: 0.8, n: 90 },
      },
    })),
  };
}

/** The whole screen with a chart, a run open and a comparison, as a person using a screen reader would find it. */
describe('AdminEval (accessibility)', () => {
  let root: HTMLElement;

  function accessibleName(element: Element): string {
    const labelledBy = element.getAttribute('aria-labelledby');
    if (labelledBy) {
      return labelledBy
        .split(' ')
        .map((id) => root.ownerDocument.getElementById(id)?.textContent ?? '')
        .join(' ')
        .trim();
    }
    const label = element.getAttribute('aria-label');
    if (label) {
      return label.trim();
    }
    if (element.id) {
      const field = root.querySelector(`label[for="${element.id}"]`);
      if (field) {
        return (field.textContent ?? '').trim();
      }
    }
    return (element.textContent ?? '').trim();
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'admin-eval', component: AdminEval }]),
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: () => undefined } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/admin-eval', AdminEval);
    root = harness.routeNativeElement as HTMLElement;
    const settle = async (): Promise<void> => {
      harness.detectChanges();
      await harness.fixture.whenStable();
      harness.detectChanges();
    };

    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`).flush(listing());
    await settle();
    (root.querySelector('app-run-list tbody button.view-detail') as HTMLButtonElement).click();
    await settle();
    http.expectOne(`${EVAL_HISTORY_URL}/runs/t031-1`).flush(RUN_BODY);
    await settle();
    const compare = Array.from(
      root.querySelectorAll('app-run-list tbody tr')[1].querySelectorAll('button'),
    ).find((button) =>
      (button.textContent ?? '').includes('Comparar con la referencia'),
    ) as HTMLButtonElement;
    compare.click();
    await settle();
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`).flush(COMPARISON_BODY);
    await settle();
  });

  it('has a single level-one heading', () => {
    expect(root.querySelectorAll('h1')).toHaveLength(1);
  });

  it('names every control, as a label, an aria-label or its own text', () => {
    const controls = Array.from(root.querySelectorAll('button, select, input, textarea'));

    expect(controls.length).toBeGreaterThan(10);
    for (const control of controls) {
      expect(accessibleName(control), control.outerHTML.slice(0, 120)).not.toBe('');
    }
  });

  it('hides the decorative icons from assistive technology', () => {
    const icons = Array.from(root.querySelectorAll('mat-icon'));

    expect(icons.length).toBeGreaterThan(0);
    for (const icon of icons) {
      expect(icon.getAttribute('aria-hidden'), icon.outerHTML).toBe('true');
    }
  });

  it('keeps every table inside a region that has a name and can be reached with the keyboard', () => {
    const tables = Array.from(root.querySelectorAll('table'));

    expect(tables.length).toBeGreaterThanOrEqual(4);
    for (const table of tables) {
      const region = table.closest('[role="region"]');
      expect(region, 'a table outside a region').not.toBeNull();
      expect(region?.getAttribute('tabindex')).toBe('0');
      expect((region?.getAttribute('aria-label') ?? '').length).toBeGreaterThan(0);
    }
  });

  it('gives the chart a name and a description', () => {
    const chart = root.querySelector('svg.plot') as SVGElement;

    expect(chart.getAttribute('role')).toBe('img');
    expect(accessibleName(chart).length).toBeGreaterThan(0);
  });

  it('says in words which run is chosen and which is the reference, not only with colour', () => {
    const rows = root.querySelectorAll('app-run-list tbody tr');

    expect(rows[0].getAttribute('aria-current')).toBe('true');
    expect(rows[0].textContent).toContain('Elegida');
    expect(rows[0].textContent).toContain('Referencia');
    expect(rows[1].textContent).not.toContain('Elegida');
  });

  it('has no repeated ids, which would break the labels that point to them', () => {
    const ids = Array.from(root.querySelectorAll('[id]')).map((element) => element.id);

    expect(new Set(ids).size).toBe(ids.length);
  });

  it('writes the warning of the comparison as text, not only with an icon or a colour', () => {
    const warning = root.querySelector('app-run-comparison .warnings') as HTMLElement;

    expect(warning.textContent).toContain('Puede no reflejar un cambio de calidad');
  });
});
