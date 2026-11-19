import { HttpEvent, HttpEventType } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { DocumentSummary, DocumentUploadResponse, UploadItem, UploadState } from './document';
import { DocumentsApiClient } from './documents-api.client';
import { uploadFailure } from './upload-error';
import { validateUpload } from './upload-validation';

/** While a document is pending, the list is read again this often (research R8). */
export const POLL_INTERVAL_MS = 3_000;
/** After this many failed reads in a row, the list is read less often. */
export const POLL_ERRORS_BEFORE_BACKOFF = 5;
export const POLL_BACKOFF_MS = 10_000;
/** The automatic refresh stops after this long; the "Actualizar" button starts it again. */
export const POLL_MAX_DURATION_MS = 10 * 60 * 1_000;

export const LOAD_ERROR_MESSAGE =
  'No se pudo cargar la lista de documentos. Se muestra el último estado conocido.';

const ACTIVE_STATES: readonly UploadState[] = ['sending', 'processing'];
const CANCELLABLE_STATES: readonly UploadState[] = ['queued', 'sending', 'processing'];
const RETRYABLE_STATES: readonly UploadState[] = ['failed', 'cancelled'];

/**
 * State of the documents screen: the list of the knowledge base, with a bounded refresh while documents are pending,
 * and the queue of uploads, sent one at a time. Provided by the screen, so timers and requests die with it.
 */
@Injectable()
export class DocumentsStore {
  private readonly client = inject(DocumentsApiClient);

  readonly documents = signal<DocumentSummary[]>([]);
  readonly loading = signal(false);
  /** True once a list has been read; until then an empty list means "not known yet", not "no documents". */
  readonly loaded = signal(false);
  readonly loadError = signal<string | null>(null);
  /** True when the automatic refresh gave up after its maximum duration with documents still pending. */
  readonly refreshStopped = signal(false);
  readonly uploads = signal<UploadItem[]>([]);

  readonly hasPending = computed(() => this.documents().some((d) => d.status === 'PENDING'));
  readonly isEmpty = computed(() => this.loaded() && this.documents().length === 0);
  readonly isUploading = computed(() =>
    this.uploads().some((upload) => ACTIVE_STATES.includes(upload.state)),
  );

  private listSubscription?: Subscription;
  private uploadSubscription?: Subscription;
  private activeUploadId?: string;
  private pollTimer?: ReturnType<typeof setTimeout>;
  private pollStartedAt?: number;
  private consecutiveErrors = 0;
  private nextUploadId = 0;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.clearPoll();
      this.listSubscription?.unsubscribe();
      this.uploadSubscription?.unsubscribe();
    });
  }

  /** Reads the list; on failure the last known list stays on screen. */
  load(): void {
    this.clearPoll();
    this.listSubscription?.unsubscribe();
    this.loading.set(true);
    this.listSubscription = this.client.list().subscribe({
      next: (documents) => {
        this.documents.set(documents);
        this.loaded.set(true);
        this.loadError.set(null);
        this.consecutiveErrors = 0;
        this.loading.set(false);
        this.schedulePoll();
      },
      error: () => {
        this.consecutiveErrors++;
        this.loadError.set(LOAD_ERROR_MESSAGE);
        this.loading.set(false);
        this.schedulePoll();
      },
    });
  }

  /** Reads the list now and starts the automatic refresh afresh (button "Actualizar" / "Reintentar"). */
  reload(): void {
    this.pollStartedAt = undefined;
    this.consecutiveErrors = 0;
    this.refreshStopped.set(false);
    this.load();
  }

  /** Queues the chosen files; those that cannot be sent are marked rejected with their cause, without a request. */
  addFiles(files: Iterable<File>): void {
    const items = [...files].map((file): UploadItem => {
      const rejection = validateUpload(file);
      return {
        id: `upload-${++this.nextUploadId}`,
        file,
        name: file.name,
        size: file.size,
        state: rejection ? 'rejected' : 'queued',
        percent: 0,
        ...(rejection ? { message: rejection } : {}),
      };
    });
    if (items.length === 0) {
      return;
    }
    this.uploads.update((uploads) => [...uploads, ...items]);
    this.processNext();
  }

  cancel(id: string): void {
    const upload = this.find(id);
    if (!upload || !CANCELLABLE_STATES.includes(upload.state)) {
      return;
    }
    if (id === this.activeUploadId) {
      this.uploadSubscription?.unsubscribe();
      this.finishActive();
    }
    this.patch(id, { state: 'cancelled' });
    this.processNext();
  }

  /** Sends the same file again; it keeps the file the administrator chose. */
  retry(id: string): void {
    const upload = this.find(id);
    if (!upload || !RETRYABLE_STATES.includes(upload.state)) {
      return;
    }
    this.uploads.update((uploads) =>
      uploads.map((item) =>
        item.id === id ? { ...item, state: 'queued', percent: 0, message: undefined } : item,
      ),
    );
    this.processNext();
  }

  /** Removes a finished upload from the list of uploads. */
  dismiss(id: string): void {
    const upload = this.find(id);
    if (!upload || CANCELLABLE_STATES.includes(upload.state)) {
      return;
    }
    this.uploads.update((uploads) => uploads.filter((item) => item.id !== id));
  }

  private processNext(): void {
    if (this.activeUploadId || this.destroyed) {
      return;
    }
    const next = this.uploads().find((upload) => upload.state === 'queued');
    if (next) {
      this.start(next);
    }
  }

  private start(upload: UploadItem): void {
    const id = upload.id;
    this.activeUploadId = id;
    this.patch(id, { state: 'sending', percent: 0 });
    this.uploadSubscription = this.client.upload(upload.file).subscribe({
      next: (event) => this.onUploadEvent(id, event),
      error: (error: unknown) => {
        const failure = uploadFailure(error);
        this.patch(id, { state: failure.retryable ? 'failed' : 'rejected', message: failure.message });
        this.finishActive();
        this.processNext();
      },
    });
  }

  private onUploadEvent(id: string, event: HttpEvent<DocumentUploadResponse>): void {
    if (event.type === HttpEventType.UploadProgress) {
      const upload = this.find(id);
      const total = event.total ?? upload?.size ?? 0;
      const percent = total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : 0;
      const sent = total > 0 && event.loaded >= total;
      this.patch(id, {
        percent: Math.max(upload?.percent ?? 0, percent),
        ...(sent ? { state: 'processing' as const } : {}),
      });
    } else if (event.type === HttpEventType.Response) {
      const body = event.body;
      this.patch(id, {
        state: 'done',
        percent: 100,
        replaced: body?.replaced ?? false,
        chunkCount: body?.chunkCount,
      });
      this.finishActive();
      this.reload();
      this.processNext();
    }
  }

  private finishActive(): void {
    this.activeUploadId = undefined;
    this.uploadSubscription = undefined;
  }

  private schedulePoll(): void {
    if (this.destroyed) {
      return;
    }
    if (!this.hasPending()) {
      this.pollStartedAt = undefined;
      return;
    }
    const now = Date.now();
    this.pollStartedAt ??= now;
    if (now - this.pollStartedAt >= POLL_MAX_DURATION_MS) {
      this.refreshStopped.set(true);
      return;
    }
    const delay =
      this.consecutiveErrors >= POLL_ERRORS_BEFORE_BACKOFF ? POLL_BACKOFF_MS : POLL_INTERVAL_MS;
    this.pollTimer = setTimeout(() => {
      this.pollTimer = undefined;
      this.load();
    }, delay);
  }

  private clearPoll(): void {
    if (this.pollTimer !== undefined) {
      clearTimeout(this.pollTimer);
      this.pollTimer = undefined;
    }
  }

  private find(id: string): UploadItem | undefined {
    return this.uploads().find((upload) => upload.id === id);
  }

  private patch(id: string, patch: Partial<UploadItem>): void {
    this.uploads.update((uploads) =>
      uploads.map((upload) => (upload.id === id ? { ...upload, ...patch } : upload)),
    );
  }
}
