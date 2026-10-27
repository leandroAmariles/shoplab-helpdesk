# Contrato: cliente de streaming del chat

**Feature**: LAB-14 | Servidor: [chat-stream.md](../../LAB-12-chat-streaming-sse/contracts/chat-stream.md) (LAB-12)

Contrato interno entre `ChatStore` y el exterior (`POST /api/chat`). Los componentes no conocen `fetch`.

## `ChatStreamClient`

```ts
interface ChatStreamClient {
  /** Cold observable: la petición se hace al suscribirse y se cancela al desuscribirse. */
  stream(message: string): Observable<ChatStreamEvent>;
}
```

| Aspecto | Comportamiento |
|---------|----------------|
| Petición | `POST /api/chat`, `Content-Type: application/json`, `Accept: text/event-stream`, cuerpo `{ "message": <texto> }` |
| Ruta | Relativa (`/api/chat`): en desarrollo la atiende el proxy de LAB-13, sin CORS |
| Emisión | Un `token` por evento `token`; luego `complete()` tras emitir `done` (o `error`) |
| Cancelación | Desuscribirse ejecuta `AbortController.abort()` y cierra la lectura; no emite error |
| Decodificación | `TextDecoder` único con `stream: true`; el parser SSE recibe el texto ya completo |
| `data` inválido | JSON mal formado o evento desconocido: se ignora el evento y se registra un aviso sin contenido |
| Fin sin `done`/`error` | Si el cuerpo termina sin evento final y no hubo cancelación: `ChatStreamError(kind: 'stream')` |

## Respuestas previas al flujo (HTTP no 2xx)

| Estado | `kind` | Cuerpo |
|--------|--------|--------|
| (sin respuesta) | `network` | — |
| 400 | `validation` | JSON de error estándar; solo se conserva `code` para depurar |
| 503 | `unavailable` | ídem |
| 502, 504 (proxy o pasarela sin backend) | `network` | — |
| otros 4xx/5xx | `server` | ídem |

## Evento `error` dentro del flujo

El evento `error` del servidor (`{"code","message"}`) se emite como `ChatStreamEvent { type: 'error' }` y el cliente
completa; el *store* lo convierte en `ChatStreamError(kind: 'stream')`. Tras un `error` o un `done` no se aceptan
más eventos.

## `parseSse` (parser puro)

```ts
interface SseParser {
  /** Recibe texto decodificado en cortes arbitrarios; devuelve los eventos ya completos. */
  push(chunk: string): SseEvent[];
}
interface SseEvent { event: string; data: string }   // event por defecto: "message"
```

| Entrada | Resultado |
|---------|-----------|
| Líneas separadas por `\n` o `\r\n` | Mismo resultado |
| Evento partido entre varias llamadas a `push` (incluso un carácter por llamada) | Se emite una sola vez, completo |
| Varias líneas `data:` seguidas | Se unen con `\n` |
| Línea que empieza por `:` | Comentario, se ignora |
| Línea en blanco sin datos acumulados | No emite |
| Espacio tras `data:` | Se elimina solo el primero |

## Garantías hacia el store

| Garantía | Detalle |
|----------|---------|
| Orden | Los `token` se emiten en el orden recibido; concatenar los `content` da el texto exacto |
| Un solo final | Exactamente un `done` o un `error`, o ninguno si hubo cancelación |
| Sin fugas | Al terminar, fallar o desuscribirse se libera el lector (`reader.cancel()`) |
