# Data Model: Memoria de la conversación (LAB-24)

## Tabla `chat.conversation_turn` (migración `V2__conversation_turns.sql`)

Propiedad exclusiva del módulo `chat`. Un turno = la pregunta del cliente y la respuesta completa del bot.

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `bigint GENERATED ALWAYS AS IDENTITY` | Clave primaria; define el orden de los turnos de una conversación |
| `conversation_id` | `uuid NOT NULL` | Identificador generado por el cliente; sin clave foránea (no hay tabla de conversaciones) |
| `question` | `text NOT NULL` | Mensaje del cliente, tal cual; `CHECK (length(question) > 0)` |
| `answer` | `text NOT NULL` | Respuesta final completa, con las marcas `[n]` como se emitieron; `CHECK (length(answer) > 0)` |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | Solo informativa |

Índice: `conversation_turn_conversation_idx ON (conversation_id, id DESC)` para leer los últimos N turnos.

Consultas:

- Leer: `SELECT question, answer FROM chat.conversation_turn WHERE conversation_id = ? ORDER BY id DESC LIMIT ?`
  (el adaptador devuelve los turnos en orden cronológico).
- Guardar: `INSERT INTO chat.conversation_turn (conversation_id, question, answer) VALUES (?, ?, ?)` (una sentencia).

Reglas:

- No se actualiza ni se borra ninguna fila en esta historia. El recorte del historial es solo de lectura.
- No se guardan fuentes, fragmentos ni contexto de la base de conocimiento (FR-013); cada turno recupera el suyo.
- No hay caducidad ni borrado (FR-017). La tabla contiene texto libre del cliente: nada de ello se registra ni se mide.

## Tipos de dominio (`chat/domain/model`)

| Tipo | Campos | Reglas |
|---|---|---|
| `ConversationId` | `String value` (UUID canónico en minúsculas) | `optional(String)`: nulo → vacío (el comando lo trata como ausente); cadena vacía o no-UUID → `INVALID_CONVERSATION_ID` |
| `ConversationTurn` | `String question`, `String answer` | Ambos no vacíos |
| `StreamChatCommand` | `message`, `requestId`, `conversationId` (`String`, opcional) | El controlador solo copia el campo; la validación ocurre en el caso de uso |
| `HistorySelection` | `List<ConversationTurn> turns`, `boolean trimmed` | Resultado de `HistoryWindow.select`; `turns` en orden cronológico |
| `MemoryOperation` (enum) | `READ`, `WRITE` | `tagValue()`: `read` / `write` para la métrica |

## Clases puras de aplicación

- `HistoryWindow.select(List<ConversationTurn> turns, int maxTurns, int maxCharacters)` → `HistorySelection(turns,
  trimmed)`: el caso de uso lee `maxTurns + 1` turnos; `select` toma los `maxTurns` más recientes; mientras
  `Σ(len(question) + len(answer)) > maxCharacters` descarta el más antiguo; en las respuestas quita las marcas `[n]`
  (`\[\d+(?:\s*[,-]\s*\d+)*\]`) y los espacios que dejan. `turns` va en orden cronológico y `trimmed` es `true`
  si se descartó algún turno, por cantidad o por caracteres.
- `FollowUpQuery.compose(String message, List<ConversationTurn> history, int retrievalTurns, int maxCharacters)` →
  `String`: las preguntas de los últimos `retrievalTurns` turnos, una por línea, y al final `message`; sin historial o
  con `retrievalTurns = 0` devuelve `message`. Se descartan primero las preguntas más antiguas hasta que el resultado
  no supere `maxCharacters` (constante `FOLLOW_UP_QUERY_MAX_CHARACTERS` = 2000 en `ChatConstants`); el mensaje actual
  se conserva siempre entero aunque él solo supere el tope.

## Configuración (`MemoryProperties`, prefijo `shoplab.chat.memory`)

| Clave | Variable de entorno | Por defecto | Validación |
|---|---|---|---|
| `max-turns` | `SHOPLAB_CHAT_MEMORY_MAX_TURNS` | 5 | 0–50; `0` desactiva la memoria |
| `max-history-characters` | `SHOPLAB_CHAT_MEMORY_MAX_HISTORY_CHARACTERS` | 4000 | ≥ 200 |
| `retrieval-turns` | `SHOPLAB_CHAT_MEMORY_RETRIEVAL_TURNS` | 1 | 0–50; `0` desactiva la consulta compuesta. En ejecución se usa `min(retrieval-turns, max-turns)`, de modo que `max-turns = 0` no exige tocar esta clave |

Un valor fuera de rango impide arrancar con un mensaje que nombra la clave (como `RagProperties`).

## Estados

Un turno nace completo; no tiene ciclo de vida. El flujo de una petición con memoria:

```text
validar → leer historial → recortar → componer consulta → recuperar contexto → generar (tokens)
        → [completa] guardar turno → done        [falla/cancela] → nada se guarda
```
