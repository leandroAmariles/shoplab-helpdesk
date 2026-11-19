import { ApiError } from '../../core/api/api-error';
import { uploadFailure } from './upload-error';
import { TOO_LARGE_MESSAGE, UNSUPPORTED_FORMAT_MESSAGE } from './upload-validation';

function http(status: number, code?: string): ApiError {
  return { kind: 'http', status, message: 'x', ...(code ? { code } : {}) };
}

describe('uploadFailure', () => {
  it.each([
    [415, 'SLHD_RETR_00001', UNSUPPORTED_FORMAT_MESSAGE],
    [413, 'SLHD_RETR_00002', TOO_LARGE_MESSAGE],
    [
      422,
      'SLHD_RETR_00003',
      'El documento está vacío, no tiene texto extraíble, está dañado o no se puede trocear.',
    ],
    [400, 'SLHD_GENE_00002', 'No se pudo leer el archivo enviado.'],
  ])('a %s %s is a rejection that retrying will not fix', (status, code, message) => {
    expect(uploadFailure(http(status, code))).toEqual({ message, retryable: false });
  });

  it.each([
    [503, 'SLHD_RETR_00004', 'no está disponible'],
    [502, 'SLHD_RETR_00005', 'falló'],
    [503, 'SLHD_DBAS_00001', 'base de datos'],
  ])('a %s %s is a failure that can be retried', (status, code, fragment) => {
    const failure = uploadFailure(http(status, code));
    expect(failure.retryable).toBe(true);
    expect(failure.message).toContain(fragment);
    expect(failure.message).toContain('reintentarlo');
  });

  it('a lost connection can be retried', () => {
    const failure = uploadFailure({ kind: 'network', message: 'x' } satisfies ApiError);
    expect(failure.retryable).toBe(true);
    expect(failure.message).toContain('conexión');
  });

  it('a 413 from a proxy without the error contract reads as too large', () => {
    expect(uploadFailure(http(413))).toEqual({ message: TOO_LARGE_MESSAGE, retryable: false });
  });

  it('anything else gets a generic message and can be retried', () => {
    expect(uploadFailure(http(500, 'SLHD_GENE_00001')).retryable).toBe(true);
    expect(uploadFailure(new Error('boom'))).toEqual({
      message: 'No se pudo subir el documento. Puedes reintentarlo.',
      retryable: true,
    });
  });
});
