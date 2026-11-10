/**
 * Local checks before sending, as a courtesy: the server (and the ingest service behind it) decides in the end. The
 * limit mirrors `shoplab.retrieval.upload.max-bytes` and the ingest limit (10 MiB, specs/LAB-25 research R3).
 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

export const ACCEPTED_EXTENSIONS: readonly string[] = ['.pdf', '.md', '.markdown'];

/** Value of the `accept` attribute of the file picker. */
export const ACCEPT_ATTRIBUTE = '.pdf,.md,.markdown,application/pdf,text/markdown';

export const UNSUPPORTED_FORMAT_MESSAGE = 'Formato no admitido: solo se aceptan archivos PDF y Markdown (.md).';
export const TOO_LARGE_MESSAGE = 'El archivo supera el tamaño máximo de 10 MB.';
export const EMPTY_FILE_MESSAGE = 'El archivo está vacío.';

/** Returns the Spanish reason why the file cannot be sent, or `null` when it can. */
export function validateUpload(file: Pick<File, 'name' | 'size'>): string | null {
  const name = file.name.toLowerCase();
  if (!ACCEPTED_EXTENSIONS.some((extension) => name.endsWith(extension))) {
    return UNSUPPORTED_FORMAT_MESSAGE;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return TOO_LARGE_MESSAGE;
  }
  if (file.size === 0) {
    return EMPTY_FILE_MESSAGE;
  }
  return null;
}

/** Size for people: `512 B`, `12,3 KB`, `4,5 MB`. */
export function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString('es', { maximumFractionDigits: 1 })} ${units[unit]}`;
}
