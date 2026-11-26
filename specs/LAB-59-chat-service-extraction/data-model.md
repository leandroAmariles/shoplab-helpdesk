# Data Model: LAB-59 Extraer chat-service del monolito

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-25

## 1. Propiedad de datos

- **Base de datos destino**: `chat` (en el mismo servidor PostgreSQL 16 que `shoplab` y `retrieval`).
- **Rol dueño**: `chat` (`LOGIN`, sin privilegios de superusuario). `REVOKE CONNECT ON DATABASE chat FROM PUBLIC; GRANT CONNECT ON DATABASE chat TO chat;`.
- **Esquema**: `chat` (gestionado por Flyway en `shoplab-chat-service`, `classpath:db/migration/chat`).
- **Extensiones requeridas**: Ninguna adicional (`gen_random_uuid()` es nativa en PostgreSQL 16; no se requiere `pgvector`).
- **Soberanía**: Cero claves foráneas hacia otros esquemas o servicios; las referencias a documentos entregados (`delivered_documents`) y trazas (`trace_id`) son identificadores opacos.

---

## 2. Entidades en el esquema `chat`

El esquema se migra sin cambios estructurales desde `backend/src/main/resources/db/migration/chat/` (`V1`, `V2`, `V3`).

### 2.1. `chat.conversation_turn`

Registra cada turno completado (pregunta + respuesta completa) en una única inserción atómica.

| Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|
| `id` | `bigint` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | Orden secuencial interno de turnos |
| `conversation_id` | `uuid` | `NOT NULL` | Identificador de sesión de conversación |
| `question` | `text` | `NOT NULL`, `CHECK (length(question) > 0)` | Pregunta del cliente |
| `answer` | `text` | `NOT NULL`, `CHECK (length(answer) > 0)` | Respuesta completa entregada |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` | Fecha/hora de creación |
| `turn_id` | `uuid` | `NOT NULL DEFAULT gen_random_uuid() UNIQUE` | Identificador público devuelto en el evento SSE `done` |
| `delivered_documents` | `text[]` | `NOT NULL DEFAULT '{}'` | IDs opacos de documentos citados en el turno |
| `trace_id` | `text` | `CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')` | ID opcional de traza OTLP en Langfuse |

- **Índice**: `conversation_turn_conversation_idx ON conversation_turn (conversation_id, id DESC)`.

### 2.2. `chat.feedback`

Valoración 1:1 de un turno completado y metadatos opcionales de promoción a caso golden.

| Columna | Tipo | Restricciones | Descripción |
|---|---|---|---|
| `id` | `bigint` | `GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | Clave interna |
| `turn_ref` | `bigint` | `NOT NULL UNIQUE REFERENCES conversation_turn(id) ON DELETE CASCADE` | Vínculo 1:1 al turno |
| `rating` | `text` | `NOT NULL`, `CHECK (rating IN ('UP', 'DOWN'))` | Valoración del usuario |
| `comment` | `text` | `CHECK (comment IS NULL OR (length(btrim(comment)) > 0 AND length(comment) <= 500))` | Comentario opcional |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` | Fecha de registro |
| `updated_at` | `timestamptz` | `NOT NULL DEFAULT now()` | Fecha de última actualización |
| `promoted_case_id` | `text` | `UNIQUE`, `CHECK (promoted_case_id IS NULL OR promoted_case_id ~ '^G[0-9]{3}$')` | Identificador del caso golden promovido |
| `promoted_case_jsonl` | `text` | Ver invariante de promoción | Línea JSONL canónica del caso promovido |
| `promoted_at` | `timestamptz` | Ver invariante de promoción | Marca temporal de promoción |

- **Invariantes**:
  - Solo feedback con `rating = 'DOWN'` puede promocionarse (`feedback_promoted_only_down_chk`).
  - Los tres campos de promoción (`promoted_case_id`, `promoted_case_jsonl`, `promoted_at`) son todos nulos o todos no nulos (`feedback_promotion_complete_chk`).
- **Índice**: `feedback_rating_created_idx ON feedback (rating, created_at DESC, id DESC)`.

### 2.3. `chat.flyway_schema_history`

Historial de migraciones `V1`–`V3` migrado junto con el esquema para que Flyway en `shoplab-chat-service` reconozca el estado actual sin intentar recrear tablas existentes.

---

## 3. Modelos de contrato (sin persistencia propia)

- **`AssistantSettings`**: `{ topK: int, similarityThreshold: double, maxContextCharacters: int, prompts: List<ServedPrompt> }`.
- **`ServedPrompt`**: `{ name: String, version: Integer (nullable cuando source=DEFAULT), source: String ("LANGFUSE" | "DEFAULT") }`.

---

## 4. Esquemas en `shoplab` tras LAB-59

| Esquema en `shoplab` | Estado tras LAB-59 | Acción |
|---|---|---|
| `chat` | Sin uso tras el corte | Se conserva hasta verificar en `dev`; borrado manual posterior con copia previa (FR-009) |
| `eval` | Activo (usado por `backend/` hasta LAB-130) | Sin cambios |
| `catalog`, `orders` | Datos de Olist conservados | Se retiran los módulos vacíos de `backend/` **sin ejecutar `DROP SCHEMA`**; pasan a F3/F4 (FR-017) |
| `tickets` | Esquema vacío conservado | Se retira el módulo vacío de `backend/` **sin ejecutar `DROP SCHEMA`**; pasa a F4 (FR-017) |
