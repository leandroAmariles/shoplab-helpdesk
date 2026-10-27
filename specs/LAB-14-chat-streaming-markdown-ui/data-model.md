# Data Model: Pantalla de chat con streaming y Markdown

No se persiste nada: todo el estado vive en memoria de la pestaña y se pierde al recargar. Estas son las estructuras
que usan el *store*, el cliente de streaming y los componentes.

## ChatMessage (mensaje del historial)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `id` | texto | Único dentro de la conversación; identificador estable para `track` |
| `author` | `'user' \| 'assistant'` | Define alineación, estilo y etiqueta accesible |
| `content` | texto | Usuario: texto literal. Asistente: Markdown acumulado (se sanea al renderizar) |
| `status` | `MessageStatus` | Solo el asistente cambia de estado; el del usuario nace `complete` |
| `error` | `ChatErrorKind` opcional | Presente solo si `status = failed` |

### MessageStatus (estados del mensaje del asistente)

```text
            token*                 done
streaming ──────────▶ streaming ─────────▶ complete
    │  │                  │
    │  └── stop() ────────┴──────────────▶ stopped
    └───── error / corte de conexión ────▶ failed
```

- Nace `streaming` al enviar (antes del primer fragmento: burbuja vacía con indicador de escritura).
- `complete`, `stopped` y `failed` son finales; un reintento crea un mensaje de asistente **nuevo**, no reabre el
  fallido.
- `stopped` y `failed` conservan el texto recibido. `stopped` no es un error.
- Un `complete` con `content` vacío se muestra como «sin respuesta» (respuesta vacía del modelo, no es un error).

## Conversation (estado del store)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `messages` | lista de `ChatMessage` | Orden de creación; solo el último puede estar `streaming` |
| `isStreaming` | derivado | Verdadero si el último mensaje está `streaming`; bloquea el envío |
| `lastUserMessage` | derivado | Texto reenviado por «Reintentar»; vacío si no hay |
| `draft` | texto | Contenido del campo de entrada; se vacía al enviar |

**Reglas de validación del envío** (espejo de LAB-12 FR-007):

| Condición | Resultado |
|-----------|-----------|
| `draft.trim()` vacío | Envío deshabilitado, sin petición |
| `draft.length > 4000` | Envío deshabilitado, contador en estado de error |
| `draft.length = 4000` | Se envía |
| `isStreaming` | Envío deshabilitado hasta `complete`, `stopped` o `failed` |

El contador de longitud se muestra a partir de 3500 caracteres. El texto enviado conserva el contenido tal cual
(sin recortar espacios internos); solo se usa `trim()` para decidir si está vacío.

## ChatStreamEvent (eventos que emite el cliente)

Unión discriminada, derivada del contrato de LAB-12 ([chat-stream.md](../LAB-12-chat-streaming-sse/contracts/chat-stream.md)):

| `type` | Carga | Significado |
|--------|-------|-------------|
| `token` | `content: string` | Fragmento de la respuesta, en orden de generación (puede ser vacío o solo espacios) |
| `done` | `fragments: number` | Respuesta completa; último evento correcto |
| `error` | `code: string`, `message: string` | Fallo tras iniciar el flujo; último evento |

## ChatStreamError (fallo del cliente)

| Campo | Tipo | Reglas |
|-------|------|--------|
| `kind` | `ChatErrorKind` | Ver tabla siguiente |
| `status` | número opcional | Estado HTTP si lo hubo |
| `code`, `detail` | texto opcional | Solo para depuración; **nunca** se muestran al cliente |

| `ChatErrorKind` | Origen | Mensaje al cliente (español) |
|-----------------|--------|------------------------------|
| `network` | `fetch` falla sin respuesta (backend caído) | No se pudo conectar con el servidor. Inténtalo de nuevo |
| `validation` | HTTP 400 | El mensaje no es válido: debe tener entre 1 y 4000 caracteres |
| `unavailable` | HTTP 503 | El asistente no está disponible en este momento. Inténtalo en unos minutos |
| `server` | Otro HTTP 5xx / inesperado | Ocurrió un error en el servidor. Inténtalo de nuevo |
| `stream` | Evento `error` o corte tras iniciar | La respuesta se interrumpió antes de terminar |

La cancelación del usuario (`AbortError` provocado por `stop()` o por salir de la pantalla) **no** produce
`ChatStreamError`.
