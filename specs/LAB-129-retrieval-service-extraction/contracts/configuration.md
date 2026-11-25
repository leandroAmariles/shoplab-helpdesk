# Contrato: configuración

## retrieval-service

| Variable | Local (compose) | Dev | Secreto |
|---|---|---|---|
| `POSTGRES_URL` | `jdbc:postgresql://postgres:5432/retrieval` | igual | no |
| `POSTGRES_USER` | `retrieval` | igual | no |
| `POSTGRES_PASSWORD` | `RETRIEVAL_DB_PASSWORD` | `/shoplab/dev/retrieval-service` | sí |
| `SPRING_AI_OPENAI_BASE_URL` | `http://litellm:4000/v1` | igual | no |
| `SPRING_AI_OPENAI_API_KEY` | `LITELLM_KEY_RETRIEVAL` | `/shoplab/dev/litellm` | sí |
| `SHOPLAB_RETRIEVAL_INGEST_BASE_URL` | `http://ingest:8000` | igual | no |
| `SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME` | `bge-m3` | igual | no |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true` | `true` | no |

El resto de `shoplab.retrieval.*` (límites de subida, lotes, `auto-index`, `reindex-on-startup`) conserva sus nombres y
valores por defecto. `SHOPLAB_RETRIEVAL_EMBEDDING_MODEL_NAME` **debe** coincidir con el modelo de los vectores
migrados; si no, la búsqueda responde vacío.

Puerto del contenedor 8080; en el host local, 8082.

## Clave del gateway de modelos

Nueva entrada en `keys.yaml` (local e infra): alias `retrieval-<entorno>`, `key_env: LITELLM_KEY_RETRIEVAL`,
`models: [embeddings]`, presupuesto diario 5.00 como el backend. En la release B el backend deja de tener `embeddings`.

## Monolito

| Variable | Release A | Corte | Release B |
|---|---|---|---|
| `SHOPLAB_RETRIEVAL_CLIENT_MODE` | sin definir | `http` | se elimina |
| `SHOPLAB_RETRIEVAL_CLIENT_BASE_URL` | sin definir | `http://retrieval-service:8080` | obligatoria |
| `SHOPLAB_RETRIEVAL_CLIENT_TIMEOUT` | sin definir | sin definir (15 s) | igual |
| `SHOPLAB_RETRIEVAL_EMBEDDING_AUTO_INDEX` | sin definir | `false` | se elimina |
| `SHOPLAB_RETRIEVAL_INGEST_BASE_URL` | se mantiene | se mantiene | se elimina |

## Gateway (corte)

```text
SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI=http://retrieval-service:8080
SHOPLAB_GATEWAY_ROUTES_KNOWLEDGE_URI=http://retrieval-service:8080
```

El corte en dev es **un solo commit** de `shoplab-infra` con las variables del gateway y del monolito. Revertirlo
devuelve todo al monolito (guía: `docs/fases/F5-mover-rutas-al-gateway.md`). En local, esos valores pasan a ser los
predeterminados del compose en la fase del corte.
