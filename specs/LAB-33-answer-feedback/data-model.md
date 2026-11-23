# Data Model: Feedback 👍/👎 en cada respuesta (LAB-33)

Esquema `chat`, dueño único: módulo `chat` del backend (ADR-0015). Migración nueva en
`backend/src/main/resources/db/migration/chat/V3__answer_feedback.sql`. Ninguna clave foránea sale del esquema `chat`.

```text
chat.conversation_turn 1 ──────── 0..1 chat.feedback
 (turno completado del chat)           (valoración 👍/👎 + promoción opcional a G<NNN>)
```

---

## 1. Ampliación de `chat.conversation_turn` (migración `V3`)

La tabla `chat.conversation_turn` (creada en `V2__conversation_turns.sql` por LAB-24) guarda una fila por turno completado de una conversación. En `V3` gana tres columnas para identificar cada turno de forma segura ante el cliente y conservar los metadatos que necesita la promoción a casos golden:

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | (existente, V2) |
| `conversation_id` | `uuid NOT NULL` | (existente, V2) |
| `question` | `text NOT NULL CHECK (length(question) > 0)` | (existente, V2) |
| `answer` | `text NOT NULL CHECK (length(answer) > 0)` | (existente, V2) |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | (existente, V2) |
| `turn_id` | `uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE` | **Nuevo (V3)**: identificador público del turno devuelto en el evento SSE `done` |
| `delivered_documents` | `text[] NOT NULL DEFAULT '{}'` | **Nuevo (V3)**: ids de documento (`source` sin `.md`/`.pdf`, sin duplicados, en orden de entrega) entregados como contexto en ese turno; `{}` si fue sin contexto o cortesía |
| `trace_id` | `text` | **Nuevo (V3)**: identificador de traza de Langfuse (`SHA-256(requestId)[0..32]`, 32 caracteres hex en minúsculas), o `NULL` |

Restricciones adicionales en `chat.conversation_turn`:
- `UNIQUE (turn_id)` (crea índice único para buscar el turno por `turn_id` al registrar feedback).
- `CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{32}$')`.

---

## 2. Tabla nueva `chat.feedback` (migración `V3`)

Guarda a lo sumo una valoración por turno (`UNIQUE (turn_ref)`), con comentario opcional y el vínculo con el caso golden cuando una respuesta con 👎 se promueve desde `admin-eval`.

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY` | Clave interna |
| `turn_ref` | `bigint NOT NULL UNIQUE REFERENCES conversation_turn(id) ON DELETE CASCADE` | FK interna al turno dentro del esquema `chat`; garantiza máximo 1 valoración por turno |
| `rating` | `text NOT NULL` | `'UP'` (👍) o `'DOWN'` (👎) |
| `comment` | `text` | Comentario opcional en texto plano (`NULL` si no se envió o era solo espacios); entre 1 y 500 caracteres tras recortar espacios extremos |
| `created_at` | `timestamptz NOT NULL DEFAULT now()` | Instante de la primera valoración del turno |
| `updated_at` | `timestamptz NOT NULL DEFAULT now()` | Instante de la última actualización de la valoración |
| `promoted_case_id` | `text UNIQUE` | Identificador del caso golden creado (`^G[0-9]{3}$`, p. ej. `G121`), o `NULL` si no ha sido promovida |
| `promoted_case_jsonl` | `text` | Línea JSONL exacta generada al promover el caso (`{"id": "G121", ...}`), o `NULL` si no ha sido promovida |
| `promoted_at` | `timestamptz` | Instante de promoción, o `NULL` si no ha sido promovida |

### Restricciones (`CHECK` y `UNIQUE`) en `chat.feedback`

1. `feedback_rating_chk`: `CHECK (rating IN ('UP', 'DOWN'))`
2. `feedback_comment_chk`: `CHECK (comment IS NULL OR (length(btrim(comment)) > 0 AND length(comment) <= 500))`
3. `feedback_promoted_case_id_chk`: `CHECK (promoted_case_id IS NULL OR promoted_case_id ~ '^G[0-9]{3}$')`
4. `feedback_promoted_only_down_chk`: `CHECK (promoted_case_id IS NULL OR rating = 'DOWN')` — solo un 👎 puede estar promovido.
5. `feedback_promotion_complete_chk`:
   `CHECK ((promoted_case_id IS NULL AND promoted_case_jsonl IS NULL AND promoted_at IS NULL) OR (promoted_case_id IS NOT NULL AND length(btrim(promoted_case_jsonl)) > 0 AND promoted_at IS NOT NULL))` — los tres campos de promoción están todos vacíos o todos presentes.

### Índices en `chat.feedback`

- `UNIQUE (turn_ref)` (implícito por la restricción `UNIQUE`).
- `UNIQUE (promoted_case_id)` (implícito por la restricción `UNIQUE`; impide que dos feedbacks se promuevan al mismo `G<NNN>`).
- `CREATE INDEX feedback_rating_created_idx ON feedback (rating, created_at DESC, id DESC)`: para listar con rapidez las valoraciones con `rating = 'DOWN'` ordenadas de más reciente a más antigua en `admin-eval`.

---

## 3. Reglas de validación del dominio (`chat`)

### 3.1. Registro o actualización de valoración (`SubmitFeedbackCommand`)

1. `turnId`: debe ser un UUID canónico válido (`SLHD_CHAT_00011`, HTTP 400 si el formato es inválido) y existir en `chat.conversation_turn` (`SLHD_CHAT_00012`, HTTP 404 si no existe).
2. `rating`: obligatorio, `UP` o `DOWN` (`SLHD_CHAT_00011`, HTTP 400 si falta o es otro valor).
3. `comment`: opcional; se recorta con `strip()`. Si queda vacío se convierte en `null`. Si supera 500 caracteres tras `strip()`, se rechaza con `SLHD_CHAT_00011` (HTTP 400).
4. **Inmutabilidad tras promoción**: si el turno ya tiene feedback con `promoted_case_id IS NOT NULL`, cualquier intento de modificar su voto o comentario se rechaza con `SLHD_CHAT_00013` (`FEEDBACK_ALREADY_PROMOTED`, HTTP 409).

### 3.2. Promoción de un feedback 👎 a caso golden (`PromoteFeedbackCommand` + `GoldenCaseValidator`)

1. El turno (`turnId`) debe existir y tener una valoración registrada en `chat.feedback` (`SLHD_CHAT_00012`, HTTP 404 si el turno o su valoración no existen).
2. La valoración debe tener `rating = DOWN`; si es `UP`, se rechaza con `SLHD_CHAT_00015` (`FEEDBACK_NOT_NEGATIVE`, HTTP 422).
3. La valoración no debe haber sido promovida ya a otro caso (`promoted_case_id IS NULL`); si ya está promovida, se rechaza con `SLHD_CHAT_00013` (`FEEDBACK_ALREADY_PROMOTED`, HTTP 409).
4. Validación del caso golden (`GoldenCaseValidator`, devuelve `SLHD_CHAT_00014` HTTP 422 con `detail` seguro que incluye el código `G-...`):
   - `G-ID`: `caseId` cumple `^G[0-9]{3}$` y no está asignado ya en `chat.feedback.promoted_case_id` (si ya lo usa otro feedback promovido, 409 `SLHD_CHAT_00013`).
   - `G-CATEGORY`: `category` ∈ `{payments, privacy, returns, shipping, warranty, off_topic, small_talk, injection}`.
   - `G-QUESTION-LENGTH`: `question.strip()` tiene entre 10 y 300 caracteres (2 y 300 si `category` ∈ `{small_talk, injection}`).
   - `G-ANSWER-LENGTH`: `referenceAnswer.strip()` tiene entre 20 y 800 caracteres.
   - `G-DOC-COUNT`: `expectedDocuments` tiene entre 0 y 2 ids de documento (`^[a-z0-9]+(?:-[a-z0-9]+)*$`), sin repetidos.
   - `G-CATEGORY-TOPIC`:
     - Si `category` ∈ `{off_topic, small_talk, injection}`, `expectedDocuments` debe estar vacía.
     - Si `expectedDocuments` no está vacía y `category` ∈ `{payments, privacy, returns, shipping, warranty}`, todos los documentos esperados deben pertenecer a ese tema (prefijo `<category>-`).
   - `G-NOANSWER-DIGITS`: si `expectedDocuments` está vacía (caso sin respuesta), `referenceAnswer` no puede contener ningún dígito (`0-9`).
   - `G-PII`: ni `question` ni `referenceAnswer` contienen secuencias de 8 o más dígitos (`\d{8,}`) ni direcciones de correo ajenas a `@shoplab.example`.
