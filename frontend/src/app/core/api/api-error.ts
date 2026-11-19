import { HttpContextToken } from '@angular/common/http';

export interface ApiError {
  /** `network` when there is no response (backend down); `http` for 4xx/5xx. */
  kind: 'network' | 'http';
  status?: number;
  /** Generic message in Spanish, ready to show. */
  message: string;
  /** Code of the backend error contract (`SLHD_<MODULE>_NNNNN`), when the body carried one. */
  code?: string;
  /** Message of the backend error body (English, no internal data); screens map `code` instead of showing it. */
  detail?: string;
}

/**
 * Marks a request whose errors the calling screen shows by itself, so the interceptor does not open its generic
 * snack bar as well (the error is still turned into an {@link ApiError}).
 */
export const HANDLES_OWN_ERRORS = new HttpContextToken<boolean>(() => false);
