import {
  EMPTY_FILE_MESSAGE,
  formatSize,
  MAX_UPLOAD_BYTES,
  TOO_LARGE_MESSAGE,
  UNSUPPORTED_FORMAT_MESSAGE,
  validateUpload,
} from './upload-validation';

describe('validateUpload', () => {
  it.each(['politica.pdf', 'faq.md', 'envios.markdown', 'MAYUSCULAS.PDF', 'Guia.Md'])(
    'accepts %s',
    (name) => {
      expect(validateUpload({ name, size: 10 })).toBeNull();
    },
  );

  it.each(['notas.txt', 'foto.png', 'documento.docx', 'sin-extension', 'pdf', 'md.zip'])(
    'rejects %s for its format with a Spanish message',
    (name) => {
      expect(validateUpload({ name, size: 10 })).toBe(UNSUPPORTED_FORMAT_MESSAGE);
    },
  );

  it('accepts a file of exactly the maximum size', () => {
    expect(validateUpload({ name: 'grande.pdf', size: MAX_UPLOAD_BYTES })).toBeNull();
  });

  it('rejects a file one byte over the maximum size', () => {
    expect(validateUpload({ name: 'grande.pdf', size: MAX_UPLOAD_BYTES + 1 })).toBe(
      TOO_LARGE_MESSAGE,
    );
  });

  it('rejects an empty file', () => {
    expect(validateUpload({ name: 'vacio.md', size: 0 })).toBe(EMPTY_FILE_MESSAGE);
  });

  it('checks the format before the size', () => {
    expect(validateUpload({ name: 'grande.txt', size: MAX_UPLOAD_BYTES + 1 })).toBe(
      UNSUPPORTED_FORMAT_MESSAGE,
    );
  });

  it('uses a limit of 10 MiB, the one of the ingest service', () => {
    expect(MAX_UPLOAD_BYTES).toBe(10_485_760);
  });

  it('writes every message in Spanish', () => {
    expect(UNSUPPORTED_FORMAT_MESSAGE).toContain('Formato no admitido');
    expect(TOO_LARGE_MESSAGE).toContain('tamaño máximo');
    expect(EMPTY_FILE_MESSAGE).toContain('vacío');
  });
});

describe('formatSize', () => {
  it('formats bytes, kilobytes and megabytes', () => {
    expect(formatSize(512)).toBe('512 B');
    expect(formatSize(2048)).toBe('2 KB');
    expect(formatSize(1536)).toBe('1,5 KB');
    expect(formatSize(5 * 1024 * 1024)).toBe('5 MB');
  });
});
