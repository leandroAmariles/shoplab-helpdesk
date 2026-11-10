import {
  HttpEventType,
  HttpHeaders,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { Injector, runInInjectionContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { DocumentSummary, DocumentUploadResponse } from './document';
import { DOCUMENTS_URL } from './documents-api.client';
import {
  DocumentsStore,
  LOAD_ERROR_MESSAGE,
  POLL_BACKOFF_MS,
  POLL_INTERVAL_MS,
  POLL_MAX_DURATION_MS,
} from './documents-store';
import { TOO_LARGE_MESSAGE, UNSUPPORTED_FORMAT_MESSAGE } from './upload-validation';

function summary(name: string, status: DocumentSummary['status']): DocumentSummary {
  return {
    id: `id-${name}`,
    name,
    title: name,
    status,
    chunkCount: 3,
    error: status === 'ERROR' ? 'The embedding model failed' : null,
    updatedAt: '2026-11-10T15:00:00Z',
    indexedAt: status === 'INDEXED' ? '2026-11-10T15:00:05Z' : null,
  };
}

function file(name: string, size = 100): File {
  return new File([new Uint8Array(size)], name);
}

const UPLOADED: DocumentUploadResponse = {
  id: 'id-politica.md',
  name: 'politica.md',
  title: 'Politica',
  status: 'PENDING',
  chunkCount: 14,
  replaced: false,
};

describe('DocumentsStore', () => {
  let http: HttpTestingController;
  let store: DocumentsStore;
  let injector: Injector;
  const open = vi.fn();

  beforeEach(() => {
    open.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open } },
        DocumentsStore,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    injector = TestBed.inject(Injector);
    store = runInInjectionContext(injector, () => TestBed.inject(DocumentsStore));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const listRequests = () =>
    http.match((request) => request.method === 'GET' && request.url === DOCUMENTS_URL);
  const uploadRequests = () =>
    http.match((request) => request.method === 'POST' && request.url === DOCUMENTS_URL);

  function answerList(documents: DocumentSummary[]): void {
    const requests = listRequests();
    expect(requests).toHaveLength(1);
    requests[0].flush(documents);
  }

  function failList(): void {
    const requests = listRequests();
    expect(requests).toHaveLength(1);
    requests[0].flush(
      { code: 'SLHD_DBAS_00001', message: 'down' },
      { status: 503, statusText: 'Unavailable' },
    );
  }

  function progress(request: TestRequest, loaded: number, total: number): void {
    request.event({ type: HttpEventType.UploadProgress, loaded, total });
  }

  describe('list', () => {
    it('shows the documents of the knowledge base', () => {
      store.load();
      answerList([summary('a.md', 'INDEXED'), summary('b.pdf', 'ERROR')]);

      expect(store.documents().map((d) => d.name)).toEqual(['a.md', 'b.pdf']);
      expect(store.loaded()).toBe(true);
      expect(store.isEmpty()).toBe(false);
      expect(store.loadError()).toBeNull();
    });

    it('tells an empty knowledge base from a list not read yet', () => {
      expect(store.isEmpty()).toBe(false);

      store.load();
      answerList([]);

      expect(store.isEmpty()).toBe(true);
    });

    it('keeps the last list and shows a warning when reading it fails, without the snack bar', () => {
      store.load();
      answerList([summary('a.md', 'INDEXED')]);

      store.reload();
      failList();

      expect(store.documents().map((d) => d.name)).toEqual(['a.md']);
      expect(store.loadError()).toBe(LOAD_ERROR_MESSAGE);
      expect(open).not.toHaveBeenCalled();
    });

    it('clears the warning once the list is read again', () => {
      store.load();
      failList();

      store.reload();
      answerList([]);

      expect(store.loadError()).toBeNull();
    });
  });

  describe('bounded refresh', () => {
    beforeEach(() => vi.useFakeTimers());

    it('does not refresh when nothing is pending', () => {
      store.load();
      answerList([summary('a.md', 'INDEXED')]);

      vi.advanceTimersByTime(POLL_INTERVAL_MS * 5);

      expect(listRequests()).toHaveLength(0);
    });

    it('refreshes every 3 s while a document is pending and stops once it is indexed', () => {
      store.load();
      answerList([summary('a.md', 'PENDING')]);

      vi.advanceTimersByTime(POLL_INTERVAL_MS - 1);
      expect(listRequests()).toHaveLength(0);
      vi.advanceTimersByTime(1);
      answerList([summary('a.md', 'PENDING')]);

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      answerList([summary('a.md', 'INDEXED')]);

      expect(store.documents()[0].status).toBe('INDEXED');
      vi.advanceTimersByTime(POLL_INTERVAL_MS * 5);
      expect(listRequests()).toHaveLength(0);
    });

    it('keeps refreshing after a failure and slows down after five failures in a row', () => {
      store.load();
      answerList([summary('a.md', 'PENDING')]);

      for (let failure = 0; failure < 5; failure++) {
        vi.advanceTimersByTime(POLL_INTERVAL_MS);
        failList();
      }

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      expect(listRequests()).toHaveLength(0);
      vi.advanceTimersByTime(POLL_BACKOFF_MS - POLL_INTERVAL_MS);
      answerList([summary('a.md', 'PENDING')]);

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      expect(listRequests()).toHaveLength(1);
    });

    it('gives up after 10 minutes and says so', () => {
      store.load();
      answerList([summary('a.md', 'PENDING')]);

      let polls = 0;
      while (!store.refreshStopped() && polls < 500) {
        vi.advanceTimersByTime(POLL_INTERVAL_MS);
        answerList([summary('a.md', 'PENDING')]);
        polls++;
      }

      expect(store.refreshStopped()).toBe(true);
      expect(polls).toBe(POLL_MAX_DURATION_MS / POLL_INTERVAL_MS);
      vi.advanceTimersByTime(POLL_BACKOFF_MS * 3);
      expect(listRequests()).toHaveLength(0);
    });

    it('starts again when the list is refreshed by hand', () => {
      store.load();
      answerList([summary('a.md', 'PENDING')]);
      vi.advanceTimersByTime(POLL_MAX_DURATION_MS + POLL_INTERVAL_MS);
      for (const request of listRequests()) {
        request.flush([summary('a.md', 'PENDING')]);
      }

      store.reload();
      answerList([summary('a.md', 'PENDING')]);

      expect(store.refreshStopped()).toBe(false);
      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      expect(listRequests()).toHaveLength(1);
    });

    it('stops refreshing when the screen goes away', () => {
      store.load();
      answerList([summary('a.md', 'PENDING')]);

      TestBed.resetTestingModule();
      vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);

      expect(http.match(() => true)).toHaveLength(0);
    });
  });

  describe('uploads', () => {
    it('rejects locally a file of an unsupported format or too large, without a request', () => {
      store.addFiles([file('notas.txt'), file('grande.pdf', 10 * 1024 * 1024 + 1)]);

      expect(store.uploads().map((u) => [u.state, u.message])).toEqual([
        ['rejected', UNSUPPORTED_FORMAT_MESSAGE],
        ['rejected', TOO_LARGE_MESSAGE],
      ]);
      expect(uploadRequests()).toHaveLength(0);
    });

    it('sends the file as the part "file" of a multipart body', () => {
      store.addFiles([file('politica.md')]);

      const [request] = uploadRequests();
      expect(request.request.body).toBeInstanceOf(FormData);
      expect((request.request.body as FormData).get('file')).toBeInstanceOf(File);
      expect(request.request.reportProgress).toBe(true);
    });

    it('shows a percentage that never goes back, then processing, then the result', () => {
      store.addFiles([file('politica.md', 1000)]);
      const [request] = uploadRequests();
      const upload = () => store.uploads()[0];

      expect(upload().state).toBe('sending');
      progress(request, 250, 1000);
      expect(upload().percent).toBe(25);
      progress(request, 100, 1000);
      expect(upload().percent).toBe(25);
      progress(request, 600, 1000);
      expect([upload().state, upload().percent]).toEqual(['sending', 60]);

      progress(request, 1000, 1000);
      expect([upload().state, upload().percent]).toEqual(['processing', 100]);

      request.flush(UPLOADED, { status: 201, statusText: 'Created' });
      expect(upload()).toMatchObject({ state: 'done', chunkCount: 14, replaced: false });
    });

    it('marks a replacement as an update', () => {
      store.addFiles([file('politica.md')]);

      uploadRequests()[0].event(
        new HttpResponse({ body: { ...UPLOADED, replaced: true }, status: 200, headers: new HttpHeaders() }),
      );

      expect(store.uploads()[0]).toMatchObject({ state: 'done', replaced: true });
    });

    it('refreshes the list once a document is stored', () => {
      store.addFiles([file('politica.md')]);
      uploadRequests()[0].flush(UPLOADED, { status: 201, statusText: 'Created' });

      answerList([summary('politica.md', 'PENDING')]);

      expect(store.documents()[0].name).toBe('politica.md');
    });

    it('sends one file at a time and a failure does not stop the others', () => {
      store.addFiles([file('a.md'), file('b.md'), file('c.md')]);

      expect(store.uploads().map((u) => u.state)).toEqual(['sending', 'queued', 'queued']);
      const first = uploadRequests();
      expect(first).toHaveLength(1);

      first[0].flush({ code: 'SLHD_RETR_00005', message: 'x' }, { status: 502, statusText: 'Bad' });
      expect(store.uploads().map((u) => u.state)).toEqual(['failed', 'sending', 'queued']);

      const second = uploadRequests();
      expect(second).toHaveLength(1);
      second[0].flush(UPLOADED, { status: 201, statusText: 'Created' });
      expect(store.uploads().map((u) => u.state)).toEqual(['failed', 'done', 'sending']);
    });

    it('cancelling the upload in progress aborts it and starts the next', () => {
      store.addFiles([file('a.md'), file('b.md')]);
      const [first] = uploadRequests();

      store.cancel(store.uploads()[0].id);

      expect(first.cancelled).toBe(true);
      expect(store.uploads().map((u) => u.state)).toEqual(['cancelled', 'sending']);
      expect(uploadRequests()).toHaveLength(1);
    });

    it('a cancelled upload leaves no row and refreshes nothing', () => {
      store.addFiles([file('a.md')]);
      store.cancel(store.uploads()[0].id);

      expect(listRequests()).toHaveLength(0);
      expect(store.documents()).toEqual([]);
    });

    it('a lost connection is a failure that can be retried with the same file', () => {
      const chosen = file('a.md', 321);
      store.addFiles([chosen]);
      uploadRequests()[0].error(new ProgressEvent('error'));

      expect(store.uploads()[0]).toMatchObject({ state: 'failed' });
      expect(store.uploads()[0].message).toContain('conexión');

      store.retry(store.uploads()[0].id);

      const [again] = uploadRequests();
      const sent = (again.request.body as FormData).get('file') as File;
      expect([sent.name, sent.size]).toEqual(['a.md', 321]);
      expect(store.uploads()[0].file).toBe(chosen);
      expect(store.uploads()[0].state).toBe('sending');
    });

    it('the ingest service being down can be retried', () => {
      store.addFiles([file('a.md')]);
      uploadRequests()[0].flush(
        { code: 'SLHD_RETR_00004', message: 'down' },
        { status: 503, statusText: 'Unavailable' },
      );

      expect(store.uploads()[0].state).toBe('failed');
      expect(open).not.toHaveBeenCalled();
    });

    it('a document rejected by the server shows its cause and cannot be retried', () => {
      store.addFiles([file('a.md')]);
      uploadRequests()[0].flush(
        { code: 'SLHD_RETR_00003', message: 'invalid' },
        { status: 422, statusText: 'Unprocessable' },
      );

      expect(store.uploads()[0].state).toBe('rejected');
      expect(store.uploads()[0].message).toContain('texto extraíble');

      store.retry(store.uploads()[0].id);
      expect(uploadRequests()).toHaveLength(0);
    });

    it('after a failure the next file is sent', () => {
      store.addFiles([file('a.md'), file('b.md')]);
      uploadRequests()[0].flush(
        { code: 'SLHD_RETR_00005', message: 'x' },
        { status: 502, statusText: 'Bad Gateway' },
      );

      expect(store.uploads().map((u) => u.state)).toEqual(['failed', 'sending']);
      expect(uploadRequests()).toHaveLength(1);
    });

    it('a finished upload can be dismissed, one in progress cannot', () => {
      store.addFiles([file('a.md'), file('notas.txt')]);

      store.dismiss(store.uploads()[0].id);
      store.dismiss(store.uploads()[1].id);

      expect(store.uploads().map((u) => u.name)).toEqual(['a.md']);
      expect(store.isUploading()).toBe(true);
    });

    it('a queued upload can be cancelled before it starts', () => {
      store.addFiles([file('a.md'), file('b.md')]);

      store.cancel(store.uploads()[1].id);

      expect(store.uploads().map((u) => u.state)).toEqual(['sending', 'cancelled']);
    });
  });
});
