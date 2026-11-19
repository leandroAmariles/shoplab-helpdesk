import { DocumentStatus } from './document';

export interface StatusPresentation {
  label: string;
  icon: string;
}

/** The state is told with text and an icon, never with colour alone. */
export const STATUS_PRESENTATION: Record<DocumentStatus, StatusPresentation> = {
  PENDING: { label: 'Pendiente', icon: 'schedule' },
  INDEXED: { label: 'Indexado', icon: 'check_circle' },
  ERROR: { label: 'Error', icon: 'error' },
};

export const DEFAULT_INDEX_ERROR = 'Falló la indexación del documento';

/**
 * `retrieval` stores the default (English) text of the error code that made indexing fail (specs/LAB-21). The known
 * texts get a Spanish translation; any other text is not shown, so nothing internal ever reaches the screen.
 */
const INDEX_ERRORS: Record<string, string> = {
  'The embedding model is not available': 'El modelo de embeddings no estaba disponible',
  'The embedding model failed': 'El modelo de embeddings falló',
  'The embedding model returned a vector of an unexpected size':
    'El modelo de embeddings devolvió un vector de tamaño inesperado',
};

export function statusPresentation(status: DocumentStatus): StatusPresentation {
  return STATUS_PRESENTATION[status] ?? { label: status, icon: 'help' };
}

/** Spanish cause of the last indexing failure, or `null` when the document is not in error. */
export function indexErrorCause(status: DocumentStatus, error: string | null): string | null {
  if (status !== 'ERROR') {
    return null;
  }
  return (error && INDEX_ERRORS[error.trim()]) || DEFAULT_INDEX_ERROR;
}
