# Data Model: Gateway de modelos con LiteLLM

La historia no añade tablas a la base de datos de la aplicación ni migraciones de Flyway. Las entidades de la spec viven en
tres sitios: **configuración versionada** (modelos, precios, claves), **estado del gateway** (su Postgres: claves y gasto,
gestionado por LiteLLM) y **registro** (una línea JSON por petición). El backend solo gana dos tipos pequeños.

## Configuración versionada (`infra/litellm/`)

### Nombre lógico de modelo (`config.yaml` → `model_list[].model_name`)

| Campo | Tipo | Regla |
|---|---|---|
| `model_name` | texto | `chat`, `embeddings`, `chat-cloud` (opcional). Es lo único que piden los consumidores (FR-006). |
| `litellm_params.model` | texto | Modelo real: `ollama_chat/<modelo>` (local) o `<proveedor>/<modelo>` (cloud). |
| `litellm_params.api_base` | texto | `http://ollama:11434` (local). |
| `litellm_params.num_ctx`, `keep_alive` | número/texto | Antes en `spring.ai.ollama.chat.options`; ahora aquí (8192 / 30m por defecto). |
| `model_info.id` | texto | Identificador estable del despliegue (`chat-primary`, `chat-fallback`…), usado en el log como `served_model_id`. |
| `model_info.input_cost_per_token`, `output_cost_per_token` | decimal | Precio de referencia (USD/token), también para los locales (FR-022). Ausente ⇒ «sin precio». |

### Ruta con fallback (`router_settings` / `litellm_settings`)

| Campo | Regla |
|---|---|
| `fallbacks` | `[{ chat: [chat-fallback] }]`. Solo `chat`. `embeddings` no lleva (FR-009). |
| `num_retries` | entero pequeño (por defecto 1). |
| `request_timeout`, `stream_timeout` | segundos; el de stream acota el tiempo hasta el primer fragmento. |
| `enable_mid_stream_fallback_continuation` | `false` (caso límite de la spec). |
| `allowed_fails`, `cooldown_time` | el despliegue caído sale de rotación un tiempo y vuelve solo (FR-016). |

### Clave virtual (`keys.yaml`)

| Campo | Tipo | Regla |
|---|---|---|
| `alias` | texto | `<consumidor>-<entorno>` en minúsculas (`backend-local`, `ingest-dev`). Único. |
| `consumer` | enum | `backend` \| `ingest`. |
| `environment` | enum | `local` \| `dev`. Cada gateway solo carga las de su entorno (`GATEWAY_ENVIRONMENT`). |
| `key_env` | texto | Nombre de la variable de entorno que contiene el valor de la clave (nunca el valor). |
| `max_budget` | decimal | USD de referencia por periodo; > 0. |
| `budget_duration` | texto | `1d` por defecto; `1h`, `7d`, `30d` admitidos. |
| `models` | lista | Nombres lógicos permitidos (el backend: `chat`, `embeddings`; ingest: ninguno hoy). |

**Reglas de validación** (las aplica `bootstrap_keys.py` antes de llamar al gateway y falla con código ≠ 0 si no se cumplen):
alias único y con el patrón del gateway; `max_budget > 0`; `budget_duration` reconocida; `key_env` definida y no vacía;
ninguna clave con formato `change-me`/vacía en el entorno `dev`.

**Estados de una clave** (en el gateway): `inexistente → activa → agotada (gasto ≥ presupuesto) → activa (al reiniciar el
periodo)`; `activa|agotada → revocada` (borrado manual). `bootstrap_keys.py` solo crea (si falta) y actualiza presupuesto,
periodo y modelos (si cambiaron); nunca borra claves que no conoce.

## Estado del gateway (PostgreSQL propio, gestionado por LiteLLM)

Tablas propias de LiteLLM (`LiteLLM_VerificationToken`, `LiteLLM_SpendLogs`, …). No se mapean ni se leen desde la
aplicación. `store_prompts_in_spend_logs = false` (no se guarda contenido).

## Registro de petición (stdout del gateway)

Esquema en [contracts/log-schema.md](contracts/log-schema.md). Campos: `ts`, `consumer`, `conversation_id`,
`operation`, `request_id`, `requested_model`, `served_model`, `fallback`, `prompt_tokens`, `completion_tokens`,
`total_tokens`, `cost_usd`, `cost_status`, `latency_ms`, `ttft_ms`, `result`, `error_type`.

## Backend (dominio `chat` y `retrieval`)

### `ServedModel` (`chat/domain/model`) — record

| Campo | Tipo | Regla |
|---|---|---|
| `name` | `String` | Modelo que respondió según los metadatos de la respuesta; `"unknown"` si la respuesta no lo trae. |
| `fallback` | `boolean` | `true` si `name` ≠ `shoplab.chat.gateway.primary-model` (propiedad informativa; ver research R-03/ADR-0023). |

Se entrega a `GenerationScope.complete(usage, answer, served)` para que la traza de LAB-64 muestre el modelo servido y la
marca de fallback (FR-015). Sin dependencia de Spring AI ni de OpenAI en el dominio.

### `EmbeddingPurpose` (`retrieval/domain/model/enums`) — enum

| Valor | Uso | `user` enviado al gateway |
|---|---|---|
| `DOCUMENT_INDEX` | `DocumentEmbedder` (subida/reindexación) | `purpose:document-index` |
| `SEARCH_QUERY` | `SearchChunksUseCase` (recuperación) | `purpose:search-query` |

### Excepciones y códigos

| Tipo | Código (`ResponseCodeEnum`) | HTTP | Cuándo |
|---|---|---|---|
| `ChatBudgetExceededException` (nuevo, `TechnicalException`) | `CHAT_BUDGET_EXCEEDED` = `SLHD_CHAT_00007` | 429 | El gateway rechaza por presupuesto agotado antes del primer fragmento |
| (en `retrieval`) excepción técnica con `EMBEDDING_BUDGET_EXCEEDED` = `SLHD_RETR_00010` | `EMBEDDING_BUDGET_EXCEEDED` | 429 | Idem para embeddings |
| `ChatModelException` (existente) | `CHAT_MODEL_UNAVAILABLE` = `SLHD_CHAT_00002` | 503 | Gateway caído, 401, modelo desconocido, fallback agotado |

### Propiedades nuevas (`GatewayProperties`, `shoplab.chat.gateway`)

| Propiedad | Defecto | Regla |
|---|---|---|
| `primary-model` | `llama3.2:3b` | Solo informativa para marcar `fallback`; vacía ⇒ nunca se marca. |
| `request-timeout` | `60s` | Tiempo máximo hasta el primer fragmento que espera el backend (mayor que el del gateway). |

Detalle en [contracts/configuration.md](contracts/configuration.md).

## Relaciones

```text
keys.yaml ──(bootstrap_keys.py)──▶ clave virtual ──presupuesto──▶ gasto (Postgres del gateway)
config.yaml ──▶ nombre lógico ──▶ {principal, respaldo} ──precio──▶ coste
backend ──[nombre lógico + user + x-shoplab-request-id]──▶ gateway ──▶ línea de log ──▶ coste por conversación
gateway ──servicio──▶ Ollama (única ruta)           traza LAB-64 ◀── ServedModel (metadatos de la respuesta)
```
