export type ChatErrorKind = 'network' | 'validation' | 'unavailable' | 'server' | 'stream';

/** Failure of a chat request. `code` and `detail` are for debugging only and are never shown. */
export class ChatStreamError extends Error {
  constructor(
    readonly kind: ChatErrorKind,
    readonly status?: number,
    readonly code?: string,
    readonly detail?: string,
  ) {
    super(kind);
    this.name = 'ChatStreamError';
  }
}

const USER_MESSAGES: Record<ChatErrorKind, string> = {
  network: 'No se pudo conectar con el servidor. Inténtalo de nuevo.',
  validation: 'El mensaje no es válido: debe tener entre 1 y 4000 caracteres.',
  unavailable: 'El asistente no está disponible en este momento. Inténtalo en unos minutos.',
  server: 'Ocurrió un error en el servidor. Inténtalo de nuevo.',
  stream: 'La respuesta se interrumpió antes de terminar.',
};

export function toUserMessage(kind: ChatErrorKind): string {
  return USER_MESSAGES[kind];
}
