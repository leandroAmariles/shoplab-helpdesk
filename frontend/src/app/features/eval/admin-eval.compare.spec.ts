import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { AdminEval } from './admin-eval';
import { EVAL_HISTORY_URL } from './eval-api.client';
import { COMPARISON_BODY } from './eval-test-data';

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
      metrics: { faithfulness: { mean: 0.7, n: 96 } },
    })),
  };
}

describe('AdminEval (comparison)', () => {
  let http: HttpTestingController;
  let root: HTMLElement;
  let harness: RouterTestingHarness;

  async function open(): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/admin-eval', AdminEval);
    root = harness.routeNativeElement as HTMLElement;
    http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`).flush(listing());
    await settle();
  }

  async function settle(): Promise<void> {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function action(row: number, label: string): HTMLButtonElement {
    const rows = root.querySelectorAll('app-run-list tbody tr');
    return Array.from(rows[row].querySelectorAll('button')).find((button) =>
      (button.textContent ?? '').includes(label),
    ) as HTMLButtonElement;
  }

  function compareRequest(): TestRequest {
    return http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/compare`);
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'admin-eval', component: AdminEval }]),
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: () => undefined } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('compares a run with the one before it when no reference was chosen', async () => {
    await open();

    action(1, 'Comparar con la referencia').click();
    await settle();

    const request = compareRequest();
    expect(request.request.params.get('base')).toBe('t031-1');
    expect(request.request.params.get('target')).toBe('t031-2');
    request.flush(COMPARISON_BODY);
    await settle();

    expect(root.querySelector('app-run-comparison section')).not.toBeNull();
    expect(root.querySelector('app-run-comparison')?.textContent).toContain('Empeoraron (1)');
  });

  it('compares with the run the viewer chose as reference and marks it', async () => {
    await open();

    action(2, 'Usar como referencia').click();
    await settle();
    action(0, 'Comparar con la referencia').click();
    await settle();

    const request = compareRequest();
    expect(request.request.params.get('base')).toBe('t031-3');
    expect(request.request.params.get('target')).toBe('t031-1');
    request.flush(COMPARISON_BODY);
    await settle();
    expect(root.querySelectorAll('app-run-list tbody tr')[2].textContent).toContain('Referencia');
  });

  it('has nothing to compare the first run with until a reference is chosen', async () => {
    await open();

    expect(action(0, 'Comparar con la referencia').disabled).toBe(true);
    expect(root.querySelectorAll('app-run-list tbody tr')[0].textContent).toContain(
      'Elige una referencia',
    );
  });

  it('keeps the error of the comparison in its own place and the chart usable', async () => {
    await open();

    action(1, 'Comparar con la referencia').click();
    await settle();
    compareRequest().flush(
      { code: 'SLHD_EVAL_00006' },
      { status: 422, statusText: 'Unprocessable Content' },
    );
    await settle();

    const alert = root.querySelector('app-run-comparison .comparison-error') as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('no son del mismo tipo');
    expect(root.querySelectorAll('svg.plot g.point').length).toBeGreaterThan(0);
  });

  it('shows the comparison under the detail of the run', async () => {
    await open();

    action(1, 'Comparar con la referencia').click();
    await settle();
    compareRequest().flush(COMPARISON_BODY);
    await settle();

    const html = root.querySelector('.admin-eval')?.innerHTML ?? '';
    expect(html.indexOf('app-run-comparison')).toBeGreaterThan(html.indexOf('app-run-list'));
  });
});
