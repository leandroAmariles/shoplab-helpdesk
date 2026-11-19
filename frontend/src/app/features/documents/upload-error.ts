import { ApiError } from '../../core/api/api-error';
import { TOO_LARGE_MESSAGE, UNSUPPORTED_FORMAT_MESSAGE } from './upload-validation';

export interface UploadFailure {
  /** Spanish message to show. */
  message: string;
  /** Whether sending the same file again may work (the service was down), as opposed to a rejected document. */
  retryable: boolean;
}

/** Rejections of the document itself: retrying the same file gives the same answer. */
const REJECTIONS: Record<string, string> = {
  SLHD_RETR_00001: UNSUPPORTED_FORMAT_MESSAGE,
  SLHD_RETR_00002: TOO_LARGE_MESSAGE,
  SLHD_RETR_00003:
    'El documento está vacío, no tiene texto extraíble, está dañado o no se puede trocear.',
  SLHD_GENE_00002: 'No se pudo leer el archivo enviado.',
};

/** Technical failures: nothing was stored and the same file can be sent again. */
const FAILURES: Record<string, string> = {
  SLHD_RETR_00004: 'El servicio de procesamiento de documentos no está disponible. Puedes reintentarlo.',
  SLHD_RETR_00005: 'El servicio de procesamiento de documentos falló. Puedes reintentarlo.',
  SLHD_DBAS_00001: 'La base de datos no está disponible. Puedes reintentarlo.',
};

const NETWORK_MESSAGE = 'Se perdió la conexión con el servidor durante el envío. Puedes reintentarlo.';
const GENERIC_MESSAGE = 'No se pudo subir el documento. Puedes reintentarlo.';
const HTTP_CONTENT_TOO_LARGE = 413;

/** Turns what the upload request failed with into a Spanish message, telling rejections from failures. */
export function uploadFailure(error: unknown): UploadFailure {
  const apiError = isApiError(error) ? error : undefined;
  if (apiError?.kind === 'network') {
    return { message: NETWORK_MESSAGE, retryable: true };
  }
  const code = apiError?.code;
  if (code && REJECTIONS[code]) {
    return { message: REJECTIONS[code], retryable: false };
  }
  if (code && FAILURES[code]) {
    return { message: FAILURES[code], retryable: true };
  }
  // A proxy in front of the backend may refuse a big body without the error contract.
  if (apiError?.status === HTTP_CONTENT_TOO_LARGE) {
    return { message: TOO_LARGE_MESSAGE, retryable: false };
  }
  return { message: GENERIC_MESSAGE, retryable: true };
}

function isApiError(error: unknown): error is ApiError {
  return (
    typeof error === 'object' &&
    error !== null &&
    'kind' in error &&
    ((error as ApiError).kind === 'network' || (error as ApiError).kind === 'http')
  );
}
