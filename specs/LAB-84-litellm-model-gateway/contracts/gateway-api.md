# Contrato: backend ↔ gateway

El backend es cliente de la API compatible con OpenAI del gateway. Nada más. Este documento fija qué pide, con qué
metadatos y cómo traduce cada respuesta. Las formas exactas de los cuerpos de error se confirman en los spikes S-2 y S-6 y
se copian aquí como ejemplos reales.

## Base y autenticación

| Elemento | Valor |
|---|---|
| Base | `${SPRING_AI_OPENAI_BASE_URL}` — local `http://localhost:4000/v1`; clúster `http://litellm:4000/v1` |
| Autenticación | `Authorization: Bearer <clave virtual del consumidor>` (`SPRING_AI_OPENAI_API_KEY`) |
| Modelos que pide | `chat`, `embeddings` (nunca un nombre real) |

## Chat: `POST /chat/completions` (streaming)

Petición (la arma Spring AI; el adaptador añade lo marcado):

```json
{
  "model": "chat",
  "stream": true,
  "stream_options": { "include_usage": true },
  "user": "<conversation-id UUID>",            // añadido por el adaptador
  "messages": [ { "role": "system", "content": "…" }, … ],
  "tools": [ … ]                                // si hay herramientas
}
```

`conversationId` es opcional en `POST /api/chat`: sin él no se envía `user` y la línea de log queda con
`conversation_id: null` (se atribuye solo por `request_id`).

Cabeceras añadidas por el adaptador (`OpenAiChatOptions.customHeaders`):

| Cabecera | Valor | Fin |
|---|---|---|
| `x-shoplab-request-id` | `X-Request-Id` del chat | cruzar log del backend ↔ log del gateway ↔ traza de LAB-64 |

Respuesta: SSE `data: {chunk}` con `choices[0].delta.content`; el último chunk trae `usage`
(`prompt_tokens`, `completion_tokens`) y todos traen `model`: el **nombre del grupo** que respondió (`chat` o `chat-fallback`). Contrato de uso de tokens: igual que hoy,
ambos en cero = desconocido (`TokenUsage.unknown()`).

## Embeddings: `POST /embeddings`

```json
{ "model": "embeddings", "input": ["texto 1", "texto 2"], "user": "purpose:document-index" }
```

Respuesta: `data[i].embedding` con 1024 valores (`RetrievalConstants.EMBEDDING_DIMENSIONS`); cualquier otra dimensión sigue
siendo `EMBEDDING_DIMENSION_MISMATCH`. El backend **no** envía `dimensions`.

## Traducción de errores

| Respuesta del gateway | Clasificación del adaptador | Resultado para el usuario |
|---|---|---|
| 200 y stream completo | — | Respuesta normal |
| 200 y el stream se corta tras el primer fragmento | fallo a mitad de stream | evento de error final `CHAT_STREAM_FAILED` (como hoy) |
| 400/422, cuerpo con `type: budget_exceeded` (422 en la versión fijada) | presupuesto agotado | HTTP 429 `SLHD_CHAT_00007` / `SLHD_RETR_00010`, mensaje fijo sin detalles |
| 401 (`token_not_found_in_db`) / 403 | clave inválida | 503 `SLHD_CHAT_00002` (aviso en log del backend con el tipo, sin la clave) |
| 403 `key_model_access_denied`, 400/404 modelo desconocido | configuración del gateway o permisos de la clave | 503 `SLHD_CHAT_00002` |
| 429 de límite de ritmo, 5xx, conexión rechazada, tiempo agotado | modelo no disponible | 503 `SLHD_CHAT_00002` (embeddings: `SLHD_RETR_00006`) |

Reglas: el mensaje al usuario es siempre el `defaultMessage` del código; el cuerpo del error del gateway se registra en el
log del backend solo como `type`/`code` (nunca el texto, que puede incluir fragmentos del prompt) y se guarda en la traza
de LAB-64 como código de error del turno.

## Garantías que el backend asume del gateway

1. Un fallo del modelo principal antes del primer fragmento se resuelve dentro del gateway; el backend ve una respuesta normal.
2. El gateway no reinicia la respuesta con otro modelo a mitad del stream.
3. El nombre `model` del chunk es el del grupo que respondió (`chat` si el principal; otro nombre si el respaldo).
4. `chat-cloud`, si existe, solo se usa si el backend lo pide explícitamente (hoy no lo pide).

## Pruebas del contrato

`GatewayStubs` (WireMock) sirve exactamente estas formas: stream normal con y sin `usage`, llamada a herramienta, corte a
mitad, 400 `budget_exceeded`, 401, 404, 503, conexión cerrada y respuesta lenta. Una prueba por fila de la tabla anterior.
