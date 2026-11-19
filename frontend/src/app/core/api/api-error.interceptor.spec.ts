import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ApiError, HANDLES_OWN_ERRORS } from './api-error';
import { apiErrorInterceptor } from './api-error.interceptor';

describe('apiErrorInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  const open = vi.fn();

  beforeEach(() => {
    open.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  function failure(url: string): Promise<unknown> {
    return new Promise((resolve) => {
      http.get(url).subscribe({ error: resolve });
    });
  }

  it('lets successful responses through untouched', () => {
    let body: unknown;
    http.get('/api/health').subscribe((response) => (body = response));

    controller.expectOne('/api/health').flush({ status: 'UP' });

    expect(body).toEqual({ status: 'UP' });
    expect(open).not.toHaveBeenCalled();
  });

  it('turns a connection failure into a network ApiError', async () => {
    const result = failure('/api/health');

    controller.expectOne('/api/health').error(new ProgressEvent('error'));

    const error = (await result) as ApiError;
    expect(error.kind).toBe('network');
    expect(error.status).toBeUndefined();
    expect(error.message).toBe('No se pudo conectar con el servidor.');
    expect(open).toHaveBeenCalledOnce();
  });

  it('turns an HTTP error into an http ApiError with the status', async () => {
    const result = failure('/api/health');

    controller.expectOne('/api/health').flush('boom', { status: 500, statusText: 'Server Error' });

    const error = (await result) as ApiError;
    expect(error).toEqual({
      kind: 'http',
      status: 500,
      message: 'El servidor respondió con un error (500).',
    });
    expect(open).toHaveBeenCalledOnce();
  });

  it('exposes the code and message of the backend error body', async () => {
    const result = failure('/api/documents');

    controller
      .expectOne('/api/documents')
      .flush(
        { code: 'SLHD_RETR_00003', message: 'The document is empty' },
        { status: 422, statusText: 'Unprocessable Content' },
      );

    const error = (await result) as ApiError;
    expect(error).toEqual({
      kind: 'http',
      status: 422,
      message: 'El servidor respondió con un error (422).',
      code: 'SLHD_RETR_00003',
      detail: 'The document is empty',
    });
  });

  it('ignores a body that does not follow the error contract', async () => {
    const result = failure('/api/documents');

    controller
      .expectOne('/api/documents')
      .flush({ code: 42 }, { status: 413, statusText: 'Payload Too Large' });

    const error = (await result) as ApiError;
    expect(error.code).toBeUndefined();
    expect(error.detail).toBeUndefined();
  });

  it('does not open the snack bar when the caller shows the error itself', async () => {
    const result = new Promise<unknown>((resolve) => {
      http
        .get('/api/documents', { context: new HttpContext().set(HANDLES_OWN_ERRORS, true) })
        .subscribe({ error: resolve });
    });

    controller
      .expectOne('/api/documents')
      .flush({ code: 'SLHD_DBAS_00001', message: 'down' }, { status: 503, statusText: 'Unavailable' });

    expect(((await result) as ApiError).code).toBe('SLHD_DBAS_00001');
    expect(open).not.toHaveBeenCalled();
  });

  it('ignores requests that are not under /api', async () => {
    const result = failure('/assets/config.json');

    controller.expectOne('/assets/config.json').flush('x', { status: 404, statusText: 'Not Found' });

    expect(((await result) as { status: number }).status).toBe(404);
    expect(open).not.toHaveBeenCalled();
  });
});
