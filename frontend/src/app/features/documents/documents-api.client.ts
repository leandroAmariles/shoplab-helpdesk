import { HttpClient, HttpContext, HttpEvent } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { DocumentSummary, DocumentUploadResponse } from './document';

export const DOCUMENTS_URL = '/api/documents';

/** The documents screen shows its own errors (cause in Spanish, retry), so the generic snack bar stays quiet. */
function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

@Injectable({ providedIn: 'root' })
export class DocumentsApiClient {
  private readonly http = inject(HttpClient);

  list(): Observable<DocumentSummary[]> {
    return this.http.get<DocumentSummary[]>(DOCUMENTS_URL, { context: ownErrors() });
  }

  /**
   * Sends one file as the `file` part. The events report the bytes sent (`UploadProgress`) and then the answer;
   * unsubscribing aborts the request.
   */
  upload(file: File): Observable<HttpEvent<DocumentUploadResponse>> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<DocumentUploadResponse>(DOCUMENTS_URL, form, {
      reportProgress: true,
      observe: 'events',
      context: ownErrors(),
    });
  }
}
