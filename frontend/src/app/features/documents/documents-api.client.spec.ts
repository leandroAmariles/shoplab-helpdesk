import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { DocumentsApiClient, DOCUMENTS_URL } from './documents-api.client';

describe('DocumentsApiClient', () => {
  let client: DocumentsApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(DocumentsApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the documents with GET and handles its own errors', () => {
    let names: string[] = [];
    client.list().subscribe((documents) => (names = documents.map((d) => d.name)));

    const request = http.expectOne({ method: 'GET', url: DOCUMENTS_URL });
    expect(request.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    request.flush([{ name: 'a.md' }]);

    expect(names).toEqual(['a.md']);
  });

  it('uploads the file as the multipart part "file" reporting progress', () => {
    const file = new File(['# Hola'], 'hola.md');
    const types: HttpEventType[] = [];
    client.upload(file).subscribe((event) => types.push(event.type));

    const request = http.expectOne({ method: 'POST', url: DOCUMENTS_URL });
    expect(request.request.reportProgress).toBe(true);
    expect(request.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    const body = request.request.body as FormData;
    expect((body.get('file') as File).name).toBe('hola.md');

    request.event({ type: HttpEventType.UploadProgress, loaded: 3, total: 6 });
    request.flush({ name: 'hola.md' });

    expect(types).toContain(HttpEventType.UploadProgress);
    expect(types).toContain(HttpEventType.Response);
  });
});
