import type {
  DocumentStatus,
  DocumentSummaryResponse,
  DocumentUploadResponse,
} from '@leandroamariles/shoplab-contracts';

export type { DocumentStatus, DocumentSummaryResponse as DocumentSummary, DocumentUploadResponse };

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
