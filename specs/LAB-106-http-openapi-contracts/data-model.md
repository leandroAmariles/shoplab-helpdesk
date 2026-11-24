# Data Model: Contratos HTTP con OpenAPI y pruebas de contrato (LAB-106)

**Feature**: `feature/LAB-106-http-openapi-contracts`  
**Date**: 2026-11-24  
**Spec**: [spec.md](file:///C:/Users/Leandro/IdeaProjects/shoplab-helpdesk/specs/LAB-106-http-openapi-contracts/spec.md)

Este documento define las entidades contractuales, esquemas de intercambio HTTP (JSON / `multipart/form-data` / `text/event-stream`), nulabilidad, validaciones y metadatos de stubs que gobiernan la comunicación entre `frontend`, `chat`, `retrieval` y `eval`.

---

## 1. Esquema Común de Errores (`common-api.yaml`)

### `ApiErrorResponse`
Representa el cuerpo JSON emitido por `GlobalExceptionHandler` ante cualquier error HTTP `4xx` o `5xx` en `chat`, `retrieval` y `eval`.

| Campo | Tipo JSON | Requerido | Nullable | Descripción |
|---|---|---|---|---|
| `code` | `string` | Sí | No | Código tipado (`SLHD_<MODULO>_NNNNN`, ej. `SLHD_RETR_00009`). |
| `message` | `string` | Sí | No | Mensaje descriptivo estándar en inglés asociado al código. |
| `detail` | `string` | No | Sí | Detalle seguro adicional cuando la excepción implementa `PublicDetail`; omitido si es `null`. |

> **Nota de compatibilidad**: `ApiErrorResponse` se añade de forma aditiva a `openapi/common/v1/common-api.yaml` sin alterar `HealthStatus`, `PingResponse`, `ErrorDto` ni el `ErrorResponse` semilla de LAB-108.

---

## 2. Entidades Contractuales de `chat` (`openapi/chat/v1/chat-api.yaml`)

Convención de serialización: **camelCase**.

### 2.1. `ChatRequest` (`POST /api/chat`)
- **Cabeceras opcionales**:
  - `X-Prompt-Versions` (`string`, opcional): lista de pares `nombre=version` para fijar versiones de prompts en evaluación.
  - `X-Prompt-Token` (`string`, opcional): token requerido cuando se envía `X-Prompt-Versions`.
- **Cuerpo**:

| Campo | Tipo | Requerido | Nullable | Restricciones |
|---|---|---|---|---|
| `message` | `string` | Sí | No | Longitud entre 1 y 4000 caracteres tras trim. |
| `conversationId` | `string` (`uuid`) | No | Sí | UUID de sesión previa; omitido o `null` para turno aislado. |

### 2.2. Payloads de Eventos SSE (`POST /api/chat`, `text/event-stream`)
Cada evento SSE emite un `event: <tipo>` y un `data: <JSON>`:

1. **`ChatSseSourcesPayload`** (`event: sources`):
   - `sources`: array no vacío de `ChatSourceItem`:
     - `number` (`integer`, >= 1)
     - `title` (`string`)
     - `section` (`string`)
     - `text` (`string`)
2. **`ChatSseTokenPayload`** (`event: token`):
   - `content` (`string`): fragmento incremental de texto.
3. **`ChatSseDonePayload`** (`event: done`):
   - `fragments` (`integer`, >= 0): cantidad de fragmentos entregados como contexto.
   - `turnId` (`string`, opcional/nullable): identificador del turno persistido en `chat.conversation_turn`.
4. **`ChatSseErrorPayload`** (`event: error`):
   - `code` (`string`): código `SLHD_CHAT_*`.
   - `message` (`string`): descripción segura del fallo durante el streaming.

### 2.3. Feedback y Promoción (`PUT /api/chat/feedback/{turnId}`, `GET /api/chat/feedback`, `POST /api/chat/feedback/{turnId}/promote`)

- **`SubmitFeedbackRequest`**:
  - `rating` (`string`, requerido, enum `["UP", "DOWN"]`)
  - `comment` (`string`, opcional/nullable, máx. 500 caracteres)
- **`FeedbackResponse`**:
  - `turnId` (`string`, requerido)
  - `rating` (`string`, requerido, enum `["UP", "DOWN"]`)
  - `comment` (`string`, nullable)
  - `createdAt` (`string`, `date-time`, requerido)
  - `updatedAt` (`string`, `date-time`, requerido)
  - `promotedCaseId` (`string`, nullable)
  - `promotedAt` (`string`, `date-time`, nullable)
- **`FeedbackListResponse`**:
  - `total` (`integer`, requerido)
  - `pendingCount` (`integer`, requerido)
  - `promotedCount` (`integer`, requerido)
  - `suggestedNextCaseId` (`string`, requerido)
  - `items` (`array` de `FeedbackItemResponse`, requerido)
- **`FeedbackItemResponse`**:
  - `turnId` (`string`, req), `conversationId` (`string`, req), `question` (`string`, req), `answer` (`string`, req), `deliveredDocuments` (`array<string>`, req), `traceId` (`string`, nullable), `rating` (`"UP" | "DOWN"`, req), `comment` (`string`, nullable), `createdAt` (`date-time`, req), `updatedAt` (`date-time`, req), `promotedCaseId` (`string`, nullable), `promotedCaseJsonl` (`string`, nullable), `promotedAt` (`date-time`, nullable)
- **`PromoteFeedbackRequest`**:
  - `caseId` (`string`, req), `question` (`string`, req), `referenceAnswer` (`string`, req), `expectedDocuments` (`array<string>`, req), `category` (`string`, req)

### 2.4. `AssistantSettingsResponse` (`GET /api/assistant/settings`)

| Campo | Tipo | Requerido | Nullable | Descripción |
|---|---|---|---|---|
| `topK` | `integer` | Sí | No | Número de fragmentos solicitados a `retrieval`. |
| `similarityThreshold` | `number` (`double`) | Sí | No | Similitud mínima exigida a un fragmento. |
| `maxContextCharacters` | `integer` | Sí | No | Presupuesto máximo de caracteres de contexto enviado al LLM. |
| `prompts` | `array<ServedPromptItem>` | Sí | No | Lista en orden estable de los prompts servidos por el asistente. |

**`ServedPromptItem`**:
- `name` (`string`, requerido): nombre del prompt en Langfuse (ej. `chat-system`).
- `version` (`integer`, requerido en objeto, **nullable**): versión numérica de Langfuse o `null` cuando `source = "DEFAULT"`.
- `source` (`string`, requerido, enum `["LANGFUSE", "DEFAULT"]`): origen del texto servido.

---

## 3. Entidades Contractuales de `retrieval` (`openapi/retrieval/v1/retrieval-api.yaml`)

Convención de serialización: **camelCase**.

### 3.1. `DocumentUploadResponse` (`POST /api/documents`)
- **Petición**: `multipart/form-data` con parte obligatoria `file` (`binary`).
- **Respuestas exitosas**: `201 Created` (documento nuevo) o `200 OK` (documento existente reemplazado).
- **Campos**: `id` (`uuid`, req), `name` (`string`, req), `title` (`string`, req), `status` (`"PENDING" | "INDEXED" | "ERROR"`, req), `chunkCount` (`integer`, req), `replaced` (`boolean`, req).
- **Errores**: `400` (`SLHD_GENE_00002`), `413` (`SLHD_RETR_00002`), `415` (`SLHD_RETR_00001`), `422` (`SLHD_RETR_00003`), `502`/`503` con `ApiErrorResponse`.

### 3.2. `DocumentSummaryResponse` (`GET /api/documents`)
- Array JSON de elementos con: `id` (`uuid`, req), `name` (`string`, req), `title` (`string`, req), `status` (`"PENDING" | "INDEXED" | "ERROR"`, req), `chunkCount` (`integer`, req), `error` (`string`, nullable), `updatedAt` (`date-time`, req), `indexedAt` (`date-time`, nullable).

### 3.3. `KnowledgeSearchResponse` (`GET /api/knowledge/search`)
- **Parámetros query**: `query` (`string`, requerido funcionalmente; si falta o está en blanco el backend devuelve `422` `SLHD_RETR_00009`), `limit` (`integer`, opcional).
- **Cuerpo `200 OK`**:
  - `model` (`string`, requerido): identificador del modelo de embeddings usado en la comparación.
  - `results` (`array<KnowledgeSearchHitResponse>`, requerido): lista ordenada por similitud descendente (vacía `[]` si no hay coincidencias).
- **`KnowledgeSearchHitResponse`**:
  - `source` (`string`, req), `title` (`string`, req), `section` (`string`, req), `position` (`integer`, req), `score` (`number` `double`, req), `text` (`string`, req).
- **Error de validación**: `422 Unprocessable Content` con `ApiErrorResponse` (`code: "SLHD_RETR_00009"`).

---

## 4. Entidades Contractuales de `eval` (`openapi/eval/v1/eval-api.yaml`)

Convención de serialización: **snake_case** (respetando exactamente las anotaciones `@JsonProperty` del backend existente y el cliente Python/Angular).

### 4.1. Endpoints de escritura/consulta protegidos por `X-Eval-Token`
- **`POST /api/eval/runs`** (`EvalRunRequest` con `additionalProperties: false` → **`EvalRunSavedResponse`** `201 Created`):
  - `EvalRunRequest`: `run_id`, `kind`, `started_at`, `finished_at`, `origin`, `commit_sha`, `dataset_hash`, `cases_measured`, `cases_excluded`, `assistant_model`, `embedding_model`, `prompts` (`PromptVersionRequest`), `judge` (`JudgeRequest`), `evaluator` (`EvaluatorRequest`), `retrieval_config` (`RetrievalConfigRequest`), `results` (`ResultRequest` con `case`: `CaseRequest`). Todos los objetos de entrada fijan `additionalProperties: false` conforme a `UnknownProperty.reject`.
  - `EvalRunSavedResponse`: `run_id`, `kind`, `results` (`integer`), `new_case_revisions` (`integer`).
- **`GET /api/eval/runs/{runId}`** → **`StoredRunResponse`**:
  - `run_id`, `kind`, `started_at`, `recorded_at`.
- **`GET /api/eval/settings`** → **`EvalSettingsResponse`**:
  - `retrieval`: `{ top_k, similarity_threshold, max_context_characters }`
  - `prompts`: lista de `{ name, version (nullable), source }`

### 4.2. Endpoints de lectura histórica (`GET /api/eval/history/*`)
- **`GET /api/eval/history/runs`** (`kind`, `origin`, `limit`) → **`RunListResponse`**:
  - `kind`, `shown`, `total`, `total_all_origins`, `origins` (`{ origin, count }`), `runs` (`RunPart`: `run_id`, `kind`, `started_at`, `finished_at`, `origin`, `commit_sha` nullable, `assistant_model` nullable, `prompts` con `pinned`, `cases_measured`, `metrics` mapa de `{ mean (nullable), n }`).
- **`GET /api/eval/history/runs/{runId}`** → **`RunDetailResponse`**:
  - `run` (`RunDetailHeader`: incluye `recorded_at`, `dataset_hash`, `cases_excluded`, `embedding_model`, `judge` nullable, `evaluator` nullable, `retrieval_config` con `depth` y `ks` nullables), `summary` (`ScopePart`), `results` (`ResultDetailPart`: `case_id`, `revision`, `category`, `question`, `reference_answer`, `expected_documents`, `case_kind`, `scores`, `retrieved_documents`, `abstained` nullable, `trace_id` nullable, `trace_url` nullable).
- **`GET /api/eval/history/compare`** (`base`, `target`, `metric`, `drop`) → **`RunComparisonResponse`**:
  - `base`, `target` (`ComparedRunPart`), `metric`, `drop`, `metrics` (`ComparisonMetricPart`), `parameters` (`ComparisonParameterPart` con `affects_comparability`), `warnings` (`array<string>`), `counts` (`worse`, `better`, `same`, `no_value`, `not_comparable`, `only_base`, `only_target`), `cases` (`ComparisonCasePart` con `status`, `base_value`, `target_value`, `delta` opcionales/nullables).

---

## 5. Metadatos de Contratos y Stubs Spring Cloud Contract

| Interacción | Productor | Consumidor | Carpeta de contratos | Coordenadas de Stubs (actual) | Coordenadas tras extracción |
|---|---|---|---|---|---|
| `GET /api/knowledge/search` | `retrieval` | `chat` (`HttpKnowledgeSearchClient`) | `src/test/resources/contracts/retrieval/` | `com.shoplab:helpdesk-backend:<ver>:stubs` (subcarpeta `mappings/retrieval/`) | `com.shoplab:shoplab-retrieval-service:<ver>:stubs` (`LAB-129`) |
| `GET /api/assistant/settings` | `chat` | `eval` (`HttpAssistantSettingsClient`) | `src/test/resources/contracts/chat/` | `com.shoplab:helpdesk-backend:<ver>:stubs` (subcarpeta `mappings/chat/`) | `com.shoplab:shoplab-chat-service:<ver>:stubs` (`LAB-59`) |

---

## 6. Regla para Servicios Futuros (`catalog`, `orders`, `tickets`)

Estos módulos no poseen endpoints HTTP en el monolito actual y nacerán en repositorios propios en F3/F4 (`ADR-0031`). Cuando se implemente su primer endpoint HTTP, su entidad de contrato se registrará obligatoriamente bajo:
- `shoplab-contracts/openapi/catalog/v1/catalog-api.yaml`
- `shoplab-contracts/openapi/orders/v1/orders-api.yaml`
- `shoplab-contracts/openapi/tickets/v1/tickets-api.yaml`
