import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { catchError, throwError } from 'rxjs';
import { ApiError } from './api-error';

const API_PREFIX = '/api';
const SNACK_BAR_DURATION_MS = 5000;

function toApiError(error: HttpErrorResponse): ApiError {
  if (error.status === 0) {
    return { kind: 'network', message: 'No se pudo conectar con el servidor.' };
  }
  return {
    kind: 'http',
    status: error.status,
    message: `El servidor respondió con un error (${error.status}).`,
  };
}

export const apiErrorInterceptor: HttpInterceptorFn = (request, next) => {
  const snackBar = inject(MatSnackBar);
  const isApiCall = request.url.startsWith(API_PREFIX);

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!isApiCall || !(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }
      const apiError = toApiError(error);
      snackBar.open(apiError.message, 'Cerrar', { duration: SNACK_BAR_DURATION_MS });
      return throwError(() => apiError);
    }),
  );
};
