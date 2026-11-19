import { DEFAULT_INDEX_ERROR, indexErrorCause, statusPresentation } from './document-status';

describe('statusPresentation', () => {
  it('gives every state a Spanish label and an icon', () => {
    expect(statusPresentation('PENDING')).toEqual({ label: 'Pendiente', icon: 'schedule' });
    expect(statusPresentation('INDEXED')).toEqual({ label: 'Indexado', icon: 'check_circle' });
    expect(statusPresentation('ERROR')).toEqual({ label: 'Error', icon: 'error' });
  });
});

describe('indexErrorCause', () => {
  it('has no cause when the document is not in error', () => {
    expect(indexErrorCause('PENDING', null)).toBeNull();
    expect(indexErrorCause('INDEXED', 'The embedding model failed')).toBeNull();
  });

  it.each([
    ['The embedding model is not available', 'El modelo de embeddings no estaba disponible'],
    ['The embedding model failed', 'El modelo de embeddings falló'],
    [
      'The embedding model returned a vector of an unexpected size',
      'El modelo de embeddings devolvió un vector de tamaño inesperado',
    ],
  ])('translates "%s"', (stored, expected) => {
    expect(indexErrorCause('ERROR', stored)).toBe(expected);
  });

  it('shows the default text for an unknown cause, never the stored one', () => {
    expect(indexErrorCause('ERROR', 'java.sql.SQLException: connection reset')).toBe(
      DEFAULT_INDEX_ERROR,
    );
    expect(indexErrorCause('ERROR', null)).toBe('Falló la indexación del documento');
  });
});
