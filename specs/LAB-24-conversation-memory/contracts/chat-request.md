# Contrato: `conversationId` y memoria de la conversación

`POST /api/chat` conserva los eventos `token`, `sources`, `done` y `error` y los códigos de LAB-12, LAB-22 y LAB-23. Este
contrato añade **un campo opcional a la petición** y **dos códigos de error**.

## 1. Petición

```json
{ "message": "¿y si es internacional?", "conversationId": "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11" }
```

| Campo | Tipo | Reglas |
|---|---|---|
| `message` | cadena | Sin cambios (no vacío, ≤ `shoplab.chat.max-message-length`) |
| `conversationId` | cadena, opcional | UUID en texto canónico (36 caracteres, con guiones). Ausente o `null`: la petición se atiende **sin memoria** (comportamiento anterior). Cadena vacía u otro formato: error `SLHD_CHAT_00005` |

- El servidor normaliza el UUID a minúsculas; dos mayúsculas/minúsculas distintas son la misma conversación.
- Con `shoplab.chat.memory.max-turns=0` (sin tocar `retrieval-turns`) la memoria está desactivada: el campo se acepta y se valida, pero no se lee ni
  se escribe.
- Un identificador nuevo (sin turnos guardados) inicia una conversación: se comporta como la primera pregunta.
- El identificador es una capacidad secreta: no hay autenticación en F1. Quien lo conoce lee el historial implícitamente
  (el bot lo usa para responder). No se devuelve nunca en ninguna respuesta ni se registra.

## 2. Eventos (sin cambios de formato)

Orden con contexto y memoria: `sources`?, `token`\*, y uno final `done` o `error`.

- `done` **solo se emite después de guardar el turno**. El cliente puede enviar el siguiente mensaje al recibirlo y el
  turno ya es visible para el seguimiento.
- Si el guardado falla, el último evento es `error` con `SLHD_CHAT_00006` en lugar de `done`.
- Respuestas fallidas o canceladas: no se guarda nada; la conversación no cambia.
- La respuesta fija de «sin información» (LAB-22) también se guarda como turno.

## 3. Códigos de error (nuevos)

| Código | HTTP | Cuándo | Cuerpo / evento |
|---|---|---|---|
| `SLHD_CHAT_00005` | 400 | `conversationId` con formato no válido; antes de leer la memoria, buscar contexto o llamar al modelo | Cuerpo de error estándar |
| `SLHD_CHAT_00006` | 503 | La memoria no se puede leer (antes de empezar el flujo: respuesta HTTP) o guardar (flujo ya iniciado: evento `error`) | Cuerpo de error estándar / evento `error` |

El mensaje de ambos errores es fijo y nunca contiene el identificador ni texto de mensajes.

## 4. Qué recibe el modelo

Orden de mensajes: `system` (prompt de sistema), pares `user`/`assistant` del historial (más antiguo primero), y un
`user` final con la pregunta actual y el contexto de la base de conocimiento (LAB-22/LAB-23). Del historial:

- las preguntas van sin contexto; las respuestas van sin marcas `[n]`;
- solo entra lo que cabe en `max-turns` y `max-history-characters`; se descarta el turno más antiguo completo;
- la búsqueda de contexto usa `pregunta anterior + "\n" + pregunta actual` cuando hay historial y
  `retrieval-turns ≥ 1`.

## 5. Configuración

| Clave | Variable de entorno | Por defecto |
|---|---|---|
| `shoplab.chat.memory.max-turns` | `SHOPLAB_CHAT_MEMORY_MAX_TURNS` | 5 |
| `shoplab.chat.memory.max-history-characters` | `SHOPLAB_CHAT_MEMORY_MAX_HISTORY_CHARACTERS` | 4000 |
| `shoplab.chat.memory.retrieval-turns` | `SHOPLAB_CHAT_MEMORY_RETRIEVAL_TURNS` | 1 |

## 6. Métricas (sin texto ni `conversationId`)

| Métrica | Tipo | Etiquetas | Significado |
|---|---|---|---|
| `shoplab.chat.history.messages` | resumen | — | Mensajes de historial entregados al modelo en la petición (0 sin historial) |
| `shoplab.chat.history.trimmed` | contador | — | Peticiones en las que se descartaron turnos por los límites |
| `shoplab.chat.memory.failures` | contador | `operation=read\|write` | Fallos de la memoria |

Registro INFO por petición con memoria: `requestId`, `turns`, `characters`, `trimmed`. Nunca texto.

## 7. Cliente Angular

- Cada conversación en pantalla tiene un UUID (`crypto.randomUUID()`); se envía en cada `POST`.
- «Nueva conversación» cancela el flujo en curso, vacía los mensajes y genera un UUID nuevo.
- Recargar la página inicia una conversación nueva.
