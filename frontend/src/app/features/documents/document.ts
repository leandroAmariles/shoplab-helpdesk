/** Indexing state of a document, as `retrieval` stores it (specs/LAB-21). */
export type DocumentStatus = 'PENDING' | 'INDEXED' | 'ERROR';

/** One row of `GET /api/documents`. */
export interface DocumentSummary {
  id: string;
  name: string;
  title: string;
  status: DocumentStatus;
  chunkCount: number;
  /** Default text of the last indexing failure (English); the screen translates it. */
  error: string | null;
  updatedAt: string;
  indexedAt: string | null;
}

/** Body of a successful `POST /api/documents`. */
export interface DocumentUploadResponse {
  id: string;
  name: string;
  title: string;
  status: DocumentStatus;
  chunkCount: number;
  replaced: boolean;
}

/**
 * Where an upload is: `queued` waits its turn, `sending` moves bytes, `processing` waits for the server to chunk the
 * document. `rejected` means retrying the same file will not help (format, size, content); `failed` means it may.
 */
export type UploadState =
  | 'queued'
  | 'sending'
  | 'processing'
  | 'done'
  | 'failed'
  | 'cancelled'
  | 'rejected';

/** An attempt to send a file; it lives in the browser only. */
export interface UploadItem {
  id: string;
  file: File;
  name: string;
  size: number;
  state: UploadState;
  /** Percentage sent, 0 to 100, never going back. */
  percent: number;
  /** Spanish cause of a rejection or failure. */
  message?: string;
  replaced?: boolean;
  chunkCount?: number;
}
