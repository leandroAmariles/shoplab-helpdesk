import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ApiError, HANDLES_OWN_ERRORS } from './api-error';

const API_PREFIX = '/api';
const SNACK_BAR_DURATION_MS = 5000;

interface ErrorBody {
  code?: unknown;
  message?: unknown;
}

/** The backend error contract is `{ code, message }`; anything else (proxy pages, plain text) is ignored. */
function readBody(body: unknown): { code?: string; detail?: string } {
  if (body === null || typeof body !== 'object') {
    return {};
  }
  const { code, message } = body as ErrorBody;
  return {
    ...(typeof code === 'string' ? { code } : {}),
    ...(typeof message === 'string' ? { detail: message } : {}),
  };
}

function toApiError(error: HttpErrorResponse): ApiError {
  if (error.status === 0) {
    return { kind: 'network', message: 'No se pudo conectar con el servidor.' };
  }
  return {
    kind: 'http',
    status: error.status,
    message: `El servidor respondió con un error (${error.status}).`,
    ...readBody(error.error),
  };
}

export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const snackBar = inject(MatSnackBar);
  const isApiCall = request.url.startsWith(API_PREFIX);
  const handledByCaller = request.context.get(HANDLES_OWN_ERRORS);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!isApiCall || !(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }
      const apiError = toApiError(error);
      if (!handledByCaller) {
        snackBar.open(apiError.message, 'Cerrar', { duration: SNACK_BAR_DURATION_MS });
      }
      return throwError(() => apiError);
    }),
  );
};
