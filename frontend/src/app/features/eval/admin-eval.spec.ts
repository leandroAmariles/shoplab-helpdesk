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
import { RunKind } from './eval-history';

interface ListingOptions {
  kind?: RunKind;
  runs?: string[];
  total?: number;
  totalAllOrigins?: number;
  metrics?: Record<string, { mean: number | null; n: number }>;
}

function listing(options: ListingOptions = {}) {
  const kind = options.kind ?? 'QUALITY';
  const ids = options.runs ?? ['r1', 'r2', 'r3'];
  return {
    kind,
    shown: ids.length,
    total: options.total ?? ids.length,
    total_all_origins: options.totalAllOrigins ?? options.total ?? ids.length,
    origins: [
      { origin: 'dev', count: 2 },
      { origin: 'local', count: 1 },
    ],
    runs: ids.map((id, index) => ({
      run_id: id,
      kind,
      started_at: `2026-11-0${index + 1}T10:00:00Z`,
      finished_at: `2026-11-0${index + 1}T10:10:00Z`,
      origin: 'dev',
      commit_sha: null,
      assistant_model: null,
      prompts: [],
      cases_measured: 96,
      metrics: options.metrics ?? {
        faithfulness: { mean: 0.6 + index / 10, n: 96 },
        answer_relevancy: { mean: 0.8, n: 90 },
      },
    })),
  };
}

describe('AdminEval (chart)', () => {
  let http: HttpTestingController;
  let root: HTMLElement;
  let harness: RouterTestingHarness;

  async function open(url = '/admin-eval'): Promise<void> {
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url, AdminEval);
    root = harness.routeNativeElement as HTMLElement;
  }

  function listRequest(): TestRequest {
    return http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`);
  }

  async function settle(): Promise<void> {
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  function button(selector: string, text?: string): HTMLButtonElement {
    const found = Array.from(root.querySelectorAll<HTMLButtonElement>(selector)).find(
      (candidate) => text === undefined || (candidate.textContent ?? '').includes(text),
    );
    if (!found) {
      throw new Error(`no button ${selector} ${text ?? ''}`);
    }
    return found;
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

  it('has a single h1 and the notice of the draft dataset', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    expect(root.querySelectorAll('h1')).toHaveLength(1);
    expect(root.querySelector('.notice')?.textContent).toContain('borrador');
  });

  it('opens with the quality runs: the chart has a point for each run and the table lists them', async () => {
    await open();
    const request = listRequest();
    expect(request.request.params.get('kind')).toBe('QUALITY');
    expect(request.request.params.get('limit')).toBe('100');
    request.flush(listing());
    await settle();

    expect(root.querySelectorAll('svg.plot g.point')).toHaveLength(3);
    expect(root.querySelectorAll('app-run-list tbody tr')).toHaveLength(3);
  });

  it('starts with the kind of the address when it is a known one', async () => {
    await open('/admin-eval?tipo=RETRIEVAL');

    expect(listRequest().request.params.get('kind')).toBe('RETRIEVAL');
    http.verify();
  });

  it('ignores a kind of the address that does not exist, without an error', async () => {
    await open('/admin-eval?tipo=otra');

    expect(listRequest().request.params.get('kind')).toBe('QUALITY');
  });

  it('changes to retrieval: the chart is cleared, the new runs are asked for and the address says so', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    button('mat-button-toggle button', 'Recuperación').click();
    await settle();

    expect(root.querySelectorAll('svg.plot g.point')).toHaveLength(0);
    const request = listRequest();
    expect(request.request.params.get('kind')).toBe('RETRIEVAL');
    request.flush(
      listing({
        kind: 'RETRIEVAL',
        runs: ['q1', 'q2'],
        metrics: { reciprocal_rank: { mean: 0.5, n: 90 }, hit_at_5: { mean: 0.9, n: 80 } },
      }),
    );
    await settle();

    expect(root.querySelectorAll('svg.plot g.point')).toHaveLength(4);
    expect(TestBed.inject(Router).url).toContain('tipo=RETRIEVAL');
  });

  it('adds a metric to the chart and draws a series for it', async () => {
    await open();
    listRequest().flush(listing());
    await settle();
    expect(root.querySelectorAll('.legend li')).toHaveLength(1);

    button('.metric-options button', 'Relevancia').click();
    await settle();

    expect(root.querySelectorAll('.legend li')).toHaveLength(2);
    expect(button('.metric-options button', 'Relevancia').getAttribute('aria-pressed')).toBe(
      'true',
    );
    expect(button('.metric-options button', 'Fidelidad').getAttribute('aria-pressed')).toBe('true');
  });

  it('filters by origin and says how many runs the filter hides', async () => {
    await open();
    listRequest().flush(listing({ total: 3, totalAllOrigins: 3 }));
    await settle();

    const select = root.querySelector('select#origin-filter') as HTMLSelectElement;
    select.value = 'local';
    select.dispatchEvent(new Event('change'));
    const request = listRequest();
    expect(request.request.params.get('origin')).toBe('local');
    request.flush(listing({ runs: ['r3'], total: 1, totalAllOrigins: 3 }));
    await settle();

    const info = root.querySelector('.range-info')?.textContent ?? '';
    expect(info).toContain('Mostrando 1 de 1');
    expect(info).toContain('2 ocultas por el filtro de origen');
  });

  it('says how many runs it shows of how many there are, and widens the range on request', async () => {
    await open();
    listRequest().flush(listing({ runs: ['r1', 'r2'], total: 400, totalAllOrigins: 400 }));
    await settle();

    expect(root.querySelector('.range-info')?.textContent).toContain('Mostrando 2 de 400');

    button('button.show-more').click();
    const request = listRequest();
    expect(request.request.params.get('limit')).toBe('250');
    request.flush(listing({ runs: ['r1', 'r2'], total: 400, totalAllOrigins: 400 }));
    await settle();
  });

  it('does not offer to widen the range when every run is shown', async () => {
    await open();
    listRequest().flush(listing());
    await settle();

    expect(root.querySelector('button.show-more')).toBeNull();
  });

  it('reads again when the viewer presses «Actualizar», and never by itself', async () => {
    await open();
    listRequest().flush(listing());
    await settle();
    http.expectNone(`${EVAL_HISTORY_URL}/runs`);

    button('button.refresh').click();

    listRequest().flush(listing({ runs: ['r1', 'r2', 'r3', 'r4'] }));
    await settle();
    expect(root.querySelectorAll('svg.plot g.point')).toHaveLength(4);
  });

  describe('states', () => {
    function status(selector: string): HTMLElement | null {
      return root.querySelector<HTMLElement>(selector);
    }

    it('says that it is loading the first time, and marks the screen as busy', async () => {
      await open();
      await settle();

      const loading = status('.loading') as HTMLElement;
      expect(loading.getAttribute('role')).toBe('status');
      expect(loading.textContent).toContain('Cargando evaluaciones');
      expect(status('section.admin-eval')?.getAttribute('aria-busy')).toBe('true');
      expect(root.querySelector('svg.plot')).toBeNull();

      listRequest().flush(listing());
      await settle();

      expect(status('.loading')).toBeNull();
      expect(status('section.admin-eval')?.getAttribute('aria-busy')).toBe('false');
    });

    it('keeps what it shows while it reads again and disables the button meanwhile', async () => {
      await open();
      listRequest().flush(listing());
      await settle();

      button('button.refresh').click();
      await settle();

      expect(button('button.refresh').disabled).toBe(true);
      expect(status('.loading')).toBeNull();
      expect(root.querySelectorAll('svg.plot g.point').length).toBeGreaterThan(0);
      listRequest().flush(listing());
      await settle();
      expect(button('button.refresh').disabled).toBe(false);
    });

    it('says that there are no runs yet and how to generate them, without an empty chart', async () => {
      await open();
      listRequest().flush(listing({ runs: [], total: 0, totalAllOrigins: 0 }));
      await settle();

      const empty = status('.empty') as HTMLElement;
      expect(empty.getAttribute('role')).toBe('status');
      expect(empty.textContent).toContain('Todavía no hay ejecuciones guardadas');
      expect(empty.textContent).toContain('quality --save');
      const link = empty.querySelector('a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toContain('eval/README.md');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(root.querySelector('svg.plot')).toBeNull();
    });

    it('says that the origin filter leaves no run and offers to remove it', async () => {
      await open();
      listRequest().flush(listing());
      await settle();
      const select = root.querySelector('select#origin-filter') as HTMLSelectElement;
      select.value = 'local';
      select.dispatchEvent(new Event('change'));
      http
        .expectOne(
          (r) => r.url === `${EVAL_HISTORY_URL}/runs` && r.params.get('origin') === 'local',
        )
        .flush(listing({ runs: [], total: 0, totalAllOrigins: 3 }));
      await settle();

      const empty = status('.empty-filter') as HTMLElement;
      expect(empty.getAttribute('role')).toBe('status');
      expect(empty.textContent).toContain('Ninguna ejecución con este origen');
      expect(status('.empty')).toBeNull();

      button('.empty-filter button', 'Quitar filtro').click();
      const again = listRequest();
      expect(again.request.params.has('origin')).toBe(false);
      again.flush(listing());
      await settle();
      expect(status('.empty-filter')).toBeNull();
    });

    it('says that the evaluation could not be read, with a retry, and never shows the detail of the server', async () => {
      await open();
      listRequest().flush(
        { code: 'SLHD_EVAL_00005', detail: 'secret internal detail' },
        { status: 503, statusText: 'Service Unavailable' },
      );
      await settle();

      const alert = status('.load-error') as HTMLElement;
      expect(alert.getAttribute('role')).toBe('alert');
      expect(alert.textContent).toContain('No se pudo cargar la evaluación');
      expect(alert.textContent).not.toContain('SLHD_EVAL_00005');
      expect(alert.textContent).not.toContain('secret internal detail');
      expect(status('.empty')).toBeNull();

      button('.load-error button', 'Reintentar').click();
      listRequest().flush(listing());
      await settle();
      expect(status('.load-error')).toBeNull();
      expect(root.querySelectorAll('svg.plot g.point').length).toBeGreaterThan(0);
    });

    it('keeps the chart and the list when reading again fails', async () => {
      await open();
      listRequest().flush(listing());
      await settle();

      button('button.refresh').click();
      listRequest().flush(null, { status: 0, statusText: 'Network' });
      await settle();

      expect(status('.load-error')?.getAttribute('role')).toBe('alert');
      expect(root.querySelectorAll('svg.plot g.point').length).toBeGreaterThan(0);
      expect(root.querySelectorAll('app-run-list tbody tr')).toHaveLength(3);
    });
  });
});
