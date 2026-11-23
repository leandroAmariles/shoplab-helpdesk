import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { AdminEval } from './admin-eval';
import { EVAL_HISTORY_URL } from './eval-api.client';
import { RUN_BODY } from './eval-test-data';

function listing(ids: string[] = ['t031-1', 't031-2']) {
  return {
    kind: 'QUALITY',
    shown: ids.length,
    total: ids.length,
    total_all_origins: ids.length,
    origins: [{ origin: 'dev', count: ids.length }],
    runs: ids.map((id, index) => ({
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

describe('AdminEval (run detail)', () => {
  let http: HttpTestingController;
  let root: HTMLElement;
  let harness: RouterTestingHarness;

  async function open(url = '/admin-eval'): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, AdminEval);
    root = harness.routeNativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function listRequest(): TestRequest {
    return http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`);
  }

  function runRequest(id: string): TestRequest {
    return http.expectOne(`${EVAL_HISTORY_URL}/runs/${id}`);
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

  it('opens the detail of a run chosen in the list and writes it in the address', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    (root.querySelectorAll('app-run-list tbody button')[1] as HTMLButtonElement).click();
    await settle();
    runRequest('t031-2').flush({ ...RUN_BODY, run: { ...RUN_BODY.run, run_id: 't031-2' } });
    await settle();

    expect(root.querySelector('app-run-detail')?.textContent).toContain('t031-2');
    expect(root.querySelector('app-failing-cases')).not.toBeNull();
    expect(TestBed.inject(Router).url).toContain('ejecucion=t031-2');
    expect(root.querySelector('app-run-list tr[aria-current="true"]')?.textContent).toContain(
      't031-2',
    );
  });

  it('opens the detail of the run of a point of the chart when it is activated', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    (root.querySelector('svg.plot g.point[data-run="t031-1"]') as SVGGElement).dispatchEvent(
      new MouseEvent('click'),
    );
    await settle();
    runRequest('t031-1').flush(RUN_BODY);
    await settle();

    expect(root.querySelector('app-run-detail')).not.toBeNull();
    expect(root.querySelector('svg.plot line.selected-run')).not.toBeNull();
  });

  it('opens the run of the address when the screen opens, without waiting for the list', async () => {
    await open('/admin-eval?ejecucion=t031-1');

    runRequest('t031-1').flush(RUN_BODY);
    listRequest().flush(listing());
    await settle();

    expect(root.querySelector('app-run-detail')).not.toBeNull();
  });

  it('ignores a run id of the address that does not fit and asks for nothing', async () => {
    await open('/admin-eval?ejecucion=a%20b%2F..');

    listRequest().flush(listing());
    await settle();

    expect(root.querySelector('app-run-detail')).toBeNull();
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it('says that the run does not exist and cleans the address when it does not', async () => {
    await open('/admin-eval?ejecucion=viejo');
    listRequest().flush(listing());
    runRequest('viejo').flush(
      { code: 'SLHD_EVAL_00004' },
      { status: 404, statusText: 'Not Found' },
    );
    await settle();

    expect(root.querySelector('[role="alert"]')?.textContent).toContain('La ejecución no existe');
    expect(root.querySelector('app-run-detail')).toBeNull();
    expect(TestBed.inject(Router).url).not.toContain('ejecucion');
  });

  it('says that the run is loading', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    (root.querySelector('app-run-list tbody button') as HTMLButtonElement).click();
    await settle();

    expect(root.querySelector('.detail-loading')?.textContent).toContain('Cargando la ejecución');
    expect(root.querySelector('.detail-loading')?.getAttribute('role')).toBe('status');
    runRequest('t031-1').flush(RUN_BODY);
  });

  it('shows the error of the detail in its own place with a retry and keeps the chart usable', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    (root.querySelector('app-run-list tbody button') as HTMLButtonElement).click();
    await settle();
    runRequest('t031-1').flush(null, { status: 0, statusText: 'Network' });
    await settle();

    const alert = root.querySelector('.detail-error') as HTMLElement;
    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain('No se pudo cargar la ejecución');
    expect(root.querySelectorAll('svg.plot g.point').length).toBeGreaterThan(0);

    (alert.querySelector('button') as HTMLButtonElement).click();
    runRequest('t031-1').flush(RUN_BODY);
    await settle();

    expect(root.querySelector('.detail-error')).toBeNull();
    expect(root.querySelector('app-run-detail')).not.toBeNull();
  });

  it('closes the detail when the kind changes', async () => {
    await open();
    listRequest().flush(listing());
    await settle();
    (root.querySelector('app-run-list tbody button') as HTMLButtonElement).click();
    await settle();
    runRequest('t031-1').flush(RUN_BODY);
    await settle();

    const toggle = Array.from(
      root.querySelectorAll<HTMLButtonElement>('mat-button-toggle button'),
    ).find((button) => (button.textContent ?? '').includes('Recuperación')) as HTMLButtonElement;
    toggle.click();
    await settle();
    listRequest().flush({ ...listing([]), kind: 'RETRIEVAL', total_all_origins: 0, origins: [] });
    await settle();

    expect(root.querySelector('app-run-detail')).toBeNull();
    expect(TestBed.inject(Router).url).not.toContain('ejecucion');
  });
});
