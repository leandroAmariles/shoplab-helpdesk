# Contrato: propiedades, variables y secretos

## Backend (`application.yml`)

| Propiedad | Variable | Defecto | Notas |
|---|---|---|---|
| `spring.ai.openai.base-url` | `SPRING_AI_OPENAI_BASE_URL` | `http://localhost:4000/v1` | Debe incluir `/v1` |
| `spring.ai.openai.api-key` | `SPRING_AI_OPENAI_API_KEY` | `sk-shoplab-backend-local` | Clave virtual; marcador local |
| `spring.ai.openai.chat.model` | — | `chat` | Nombre lógico fijo (en Spring AI 2.0 las propiedades son planas) |
| `spring.ai.openai.max-retries` / `timeout` | — | `0` / `60s` | El gateway reintenta y cae al respaldo; no se duplica la capa de reintentos |
| `spring.ai.openai.embedding.model` | — | `embeddings` | Nombre lógico fijo |
| `shoplab.retrieval.embedding.model-name` | `SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME` | `bge-m3` | Nombre real guardado con cada vector (ADR-0017) |
| `shoplab.chat.gateway.primary-model` | `SHOPLAB_CHAT_GATEWAY_PRIMARY_MODEL` | `chat` | Nombre con el que el gateway informa las respuestas de su principal; otro nombre se marca como `fallback` en la traza |
| `shoplab.chat.gateway.request-timeout` | `SHOPLAB_CHAT_GATEWAY_REQUEST_TIMEOUT` | `60s` | Mayor que `timeout` del gateway |

**Eliminadas**: `spring.ai.ollama.*` (`OLLAMA_BASE_URL`, `OLLAMA_CHAT_MODEL`, `OLLAMA_KEEP_ALIVE`, `OLLAMA_NUM_CTX`,
`OLLAMA_EMBED_MODEL` en el backend). Pasan al gateway como `LITELLM_CHAT_PRIMARY_MODEL`, `LITELLM_EMBED_MODEL`… El backend ya
no contiene ninguna dirección de Ollama (FR-001); una prueba lo verifica sobre `application*.yml`.

## `infra/docker-compose.yml` (`.env.example`)

| Variable | Defecto | Notas |
|---|---|---|
| `LITELLM_IMAGE` | `…/litellm:<tag fijo de S-1>` | Nunca `latest` |
| `LITELLM_POSTGRES_IMAGE` | `docker.io/postgres:17.7` | Misma que `langfuse-postgres` |
| `LITELLM_PORT` | `4000` | |
| `LITELLM_MASTER_KEY` | `sk-shoplab-master-local-only` | Marcador; formato `sk-…` exigido por LiteLLM |
| `LITELLM_KEY_BACKEND` | `sk-shoplab-backend-local` | Clave virtual del backend (local) |
| `LITELLM_KEY_INGEST` | `sk-shoplab-ingest-local` | Clave virtual de ingest (local) |
| `LITELLM_POSTGRES_PASSWORD` | `change-me-local-only` | |
| `LITELLM_CHAT_PRIMARY_MODEL` | `${OLLAMA_CHAT_MODEL:-llama3.2:3b}` | |
| `LITELLM_CHAT_FALLBACK_MODEL` | `llama3.2:1b` | Se descarga solo si se define (ver abajo) |
| `LITELLM_EMBED_MODEL` | `${OLLAMA_EMBED_MODEL:-bge-m3}` | |
| `OLLAMA_FALLBACK_MODEL` | vacío | Si no está vacío, el `entrypoint` de Ollama también descarga este modelo |
| `CLOUD_PROVIDER_API_KEY` | vacío | Vacío ⇒ `chat-cloud` no existe (FR-011) |
| `GATEWAY_ENVIRONMENT` | `local` | Selecciona las claves de `keys.yaml` |

`ollama` deja de publicar `${OLLAMA_PORT}` por defecto y vive en una red `models` compartida solo con `litellm`. El archivo `infra/docker-compose.ollama-host.yml` (opcional) lo vuelve a publicar: lo necesita el clúster de DEV (llega al Ollama del host por `172.17.0.1:11434`) y la medición de SC-004. La cabecera del compose actualiza la descripción del perfil `core`.

## Clúster (`shoplab-infra`)

Secreto JSON en Secrets Manager `/shoplab/dev/litellm` → `ExternalSecret litellm-credentials`:

| Clave | Uso |
|---|---|
| `LITELLM_MASTER_KEY` | Administración del gateway y del Job de claves |
| `LITELLM_KEY_BACKEND` | Valor de la clave `backend-dev` |
| `LITELLM_KEY_INGEST` | Valor de la clave `ingest-dev` |
| `POSTGRES_PASSWORD` | `litellm-postgres` |
| `CLOUD_PROVIDER_API_KEY` | Opcional; si falta, `chat-cloud` no existe |

`ExternalSecret backend-litellm` entrega `SPRING_AI_OPENAI_API_KEY` (= `LITELLM_KEY_BACKEND`) al backend. Terraform añade el
secreto a la lista de `terraform/modules/secrets` y a `secrets.auto.tfvars.example` con marcadores.

`ConfigMap backend-config` (overlay `dev`): `SPRING_AI_OPENAI_BASE_URL=http://litellm:4000/v1`; se elimina
`SPRING_AI_OLLAMA_BASE_URL`.

## Seguridad

- Ningún valor real en git; los marcadores locales son obviamente locales y no sirven en `dev` (el Job de claves rechaza
  valores `change-me`/`local` si `GATEWAY_ENVIRONMENT=dev`).
- `NetworkPolicy`: solo `litellm` alcanza `ollama` (en DEV, política de salida en `backend` e `ingest` hacia el `Service` `ollama` y `172.17.0.1:11434`, porque Ollama es externo); solo `backend` e `ingest` alcanzan `litellm`; `litellm-postgres` solo recibe de `litellm`.
- Sin `Ingress` para el gateway; acceso por `kubectl port-forward` (consistente con Langfuse, ADR-0022).
- La interfaz de administración de LiteLLM no se expone ni se usa (`DISABLE_ADMIN_UI=true`).
