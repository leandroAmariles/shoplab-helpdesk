import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { apiErrorInterceptor } from '../../core/api/api-error.interceptor';
import { DocumentSummary } from './document';
import { Documents } from './documents';
import { DOCUMENTS_URL } from './documents-api.client';
import { ACCEPT_ATTRIBUTE } from './upload-validation';

const INDEXED: DocumentSummary = {
  id: '1',
  name: 'politica.md',
  title: 'Politica',
  status: 'INDEXED',
  chunkCount: 14,
  error: null,
  updatedAt: '2026-11-10T15:02:11.482Z',
  indexedAt: '2026-11-10T15:02:19.003Z',
};

describe('Documents', () => {
  let fixture: ComponentFixture<Documents>;
  let http: HttpTestingController;
  let dom: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Documents);
    dom = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  async function answerList(documents: DocumentSummary[]) {
    http.expectOne({ method: 'GET', url: DOCUMENTS_URL }).flush(documents);
    await fixture.whenStable();
  }

  const button = (text: string) =>
    [...dom.querySelectorAll('button')].find((b) => b.textContent?.trim().endsWith(text));

  it('has a Spanish heading and a keyboard-operable file picker for PDF and Markdown', async () => {
    await answerList([]);

    expect(dom.querySelector('h1')?.textContent).toBe('Documentos');
    const input = dom.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.multiple).toBe(true);
    expect(input.accept).toBe(ACCEPT_ATTRIBUTE);
    const choose = button('Elegir archivos') as HTMLButtonElement;
    const click = vi.spyOn(input, 'click');
    choose.click();
    expect(click).toHaveBeenCalled();
  });

  it('invites to upload the first document when the knowledge base is empty', async () => {
    await answerList([]);

    expect(dom.querySelector('.empty')?.textContent).toContain('Sube el primero');
    expect(dom.querySelector('table')).toBeNull();
  });

  it('shows the list of documents', async () => {
    await answerList([INDEXED]);

    expect(dom.querySelector('table')).not.toBeNull();
    expect(dom.querySelector('tbody')?.textContent).toContain('politica.md');
  });

  it('warns with a retry when the list cannot be read', async () => {
    http
      .expectOne({ method: 'GET', url: DOCUMENTS_URL })
      .flush({ code: 'SLHD_DBAS_00001', message: 'down' }, { status: 503, statusText: 'x' });
    await fixture.whenStable();

    expect(dom.querySelector('[role="alert"]')?.textContent).toContain('No se pudo cargar');
    button('Reintentar')?.click();
    await answerList([INDEXED]);

    expect(dom.querySelector('[role="alert"]')).toBeNull();
    expect(dom.querySelector('tbody')?.textContent).toContain('politica.md');
  });

  it('queues the files dropped on the drop zone', async () => {
    await answerList([]);
    const zone = dom.querySelector('.drop-zone') as HTMLElement;
    // jsdom has no DataTransfer: a plain object with the same shape is enough.
    const transfer = {
      files: [new File(['# Hola'], 'hola.md'), new File(['x'], 'notas.txt')],
      dropEffect: 'none',
    };

    const over = new Event('dragover', { cancelable: true }) as DragEvent;
    Object.defineProperty(over, 'dataTransfer', { value: transfer });
    zone.dispatchEvent(over);
    await fixture.whenStable();
    expect(zone.classList).toContain('dragging');
    expect(over.defaultPrevented).toBe(true);
    expect(transfer.dropEffect).toBe('copy');

    const drop = new Event('drop', { cancelable: true }) as DragEvent;
    Object.defineProperty(drop, 'dataTransfer', { value: transfer });
    zone.dispatchEvent(drop);
    await fixture.whenStable();

    expect(zone.classList).not.toContain('dragging');
    expect(http.match({ method: 'POST', url: DOCUMENTS_URL })).toHaveLength(1);
    expect(dom.querySelector('app-upload-list')?.textContent).toContain('Formato no admitido');
  });

  it('queues the files chosen in the picker and lets the same file be chosen again', async () => {
    await answerList([]);
    const input = dom.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      value: [new File(['# Hola'], 'hola.md')],
      configurable: true,
    });

    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    expect(http.match({ method: 'POST', url: DOCUMENTS_URL })).toHaveLength(1);
    expect(dom.querySelector('[role="progressbar"]')).not.toBeNull();
  });
});
