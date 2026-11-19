# Contrato: configuración de las trazas

**Spec**: [../spec.md](../spec.md) | **Investigación**: [../research.md](../research.md)

## Backend (`application.yml`, prefijo `shoplab.chat.tracing`)

Todas con variable de entorno equivalente (convención del repositorio: `SHOPLAB_CHAT_TRACING_<NOMBRE>`).

| Propiedad | Variable | Defecto | Descripción |
|---|---|---|---|
| `enabled` | `SHOPLAB_CHAT_TRACING_ENABLED` | `false` | Interruptor. Apagado: no se crea ningún exportador ni conexión (FR-008). |
| `endpoint` | `SHOPLAB_CHAT_TRACING_ENDPOINT` | `http://localhost:3001/api/public/otel` | Base OTLP de Langfuse; el adaptador añade `/v1/traces`. |
| `public-key` | `SHOPLAB_CHAT_TRACING_PUBLIC_KEY` | vacío | Clave pública del proyecto. |
| `secret-key` | `SHOPLAB_CHAT_TRACING_SECRET_KEY` | vacío | Clave secreta del proyecto. Nunca se registra en logs. |
| `environment` | `SHOPLAB_CHAT_TRACING_ENVIRONMENT` | `local` | Etiqueta de entorno en las trazas (`dev` en el clúster). |
| `max-content-characters` | `SHOPLAB_CHAT_TRACING_MAX_CONTENT_CHARACTERS` | `20000` | Tope por atributo de texto. |
| `queue-size` | `SHOPLAB_CHAT_TRACING_QUEUE_SIZE` | `2048` | Spans en cola; llena = se descartan y se cuentan. |
| `batch-size` | `SHOPLAB_CHAT_TRACING_BATCH_SIZE` | `256` | Spans por envío. |
| `schedule-delay` | `SHOPLAB_CHAT_TRACING_SCHEDULE_DELAY` | `2s` | Espera máxima antes de enviar un lote. |
| `export-timeout` | `SHOPLAB_CHAT_TRACING_EXPORT_TIMEOUT` | `5s` | Tiempo máximo de un envío. |
| `shutdown-timeout` | `SHOPLAB_CHAT_TRACING_SHUTDOWN_TIMEOUT` | `5s` | Tiempo máximo para vaciar la cola al apagar. |
| `pricing.<modelo-o-proveedor>.input-per-million` | — | ver abajo | USD por millón de tokens de entrada. |
| `pricing.<modelo-o-proveedor>.output-per-million` | — | ver abajo | USD por millón de tokens de salida. |

**Tabla de precios** (versionada en `application.yml`, editable por entorno): la búsqueda va primero por nombre exacto de
modelo y después por proveedor (`ollama`). Valor inicial: `ollama` con entrada y salida en `0`. Un modelo sin entrada →
`cost_status=unpriced` (no se envía coste).

**Reglas de validación** (`TracingProperties`):
- `queue-size`, `batch-size` y `max-content-characters` > 0; `batch-size` ≤ `queue-size`.
- Los precios son ≥ 0.
- `enabled=true` con claves vacías **no impide el arranque**: se registra un aviso (sin valores) y se usa el `NoOpChatTrace`.
  Un error de configuración de observabilidad no debe tumbar el chat.
- Claves incorrectas (401) o Langfuse caído: el arranque no se ve afectado; los lotes fallidos se cuentan en
  `shoplab.chat.trace.export.failures` y dejan un aviso de texto fijo (como mucho uno por minuto) sin contenido, sin URL ni
  credenciales y sin código HTTP (el exportador solo informa éxito o fallo).
- Métricas (Micrometer, sin etiquetas): `shoplab.chat.trace.dropped` (trazas descartadas por cola llena) y
  `shoplab.chat.trace.export.failures` (lotes no entregados).

## Docker Compose (`infra/docker-compose.yml`, perfil `llmops`)

Todas con defecto en el compose y listadas en `infra/.env.example` (los valores locales son marcadores obvios).

| Variable | Defecto | Uso |
|---|---|---|
| `LANGFUSE_WEB_IMAGE` | `docker.langfuse.com/langfuse/langfuse:4.77.0` | Imagen de la web/API |
| `LANGFUSE_WORKER_IMAGE` | `docker.langfuse.com/langfuse/langfuse-worker:4.77.0` | Imagen del worker |
| `LANGFUSE_CLICKHOUSE_IMAGE` | `docker.io/clickhouse/clickhouse-server:26.3.62.3` | Almacén analítico |
| `LANGFUSE_REDIS_IMAGE` | `docker.io/redis:7.4.6` | Caché y colas |
| `LANGFUSE_POSTGRES_IMAGE` | `docker.io/postgres:17.7` | Base relacional de Langfuse |
| `LANGFUSE_MINIO_IMAGE` | `cgr.dev/chainguard/minio@sha256:fb893e36…ab5a` (índice multi-arquitectura) | Almacenamiento S3 |
| `LANGFUSE_PORT` | `3001` | Puerto publicado de la interfaz (3000 es de Grafana) |
| `LANGFUSE_MINIO_PORT` | `9092` | Puerto de MinIO, solo en `127.0.0.1` |
| `LANGFUSE_INIT_PUBLIC_KEY` / `LANGFUSE_INIT_SECRET_KEY` | `pk-lf-shoplab-local` / `sk-lf-shoplab-local` | Claves del proyecto creado al arrancar |
| `LANGFUSE_INIT_USER_EMAIL` / `LANGFUSE_INIT_USER_PASSWORD` | `admin@shoplab.local` / `change-me-local-only` | Usuario inicial |
| `LANGFUSE_POSTGRES_PASSWORD`, `LANGFUSE_CLICKHOUSE_PASSWORD`, `LANGFUSE_REDIS_AUTH`, `LANGFUSE_MINIO_PASSWORD` | `change-me-local-only` | Contraseñas de los almacenes |
| `LANGFUSE_SALT`, `LANGFUSE_NEXTAUTH_SECRET` | marcadores locales | Secretos de la aplicación |
| `LANGFUSE_ENCRYPTION_KEY` | 64 caracteres hexadecimales obvios | Clave de cifrado (formato obligatorio) |

Servicios (todos con `profiles: [llmops]`, `restart: unless-stopped` y comprobación de salud):
`langfuse-web`, `langfuse-worker`, `langfuse-postgres`, `langfuse-clickhouse`, `langfuse-redis`, `langfuse-minio`.
Volúmenes con nombre: `langfuse_postgres_data`, `langfuse_clickhouse_data`, `langfuse_clickhouse_logs`,
`langfuse_minio_data`, `langfuse_redis_data`.

## Clúster DEV (`shoplab-infra`)

| Elemento | Valor |
|---|---|
| Secreto en Secrets Manager | `/shoplab/dev/langfuse` (JSON) |
| Claves del JSON | `POSTGRES_PASSWORD`, `CLICKHOUSE_PASSWORD`, `REDIS_AUTH`, `MINIO_ROOT_PASSWORD`, `SALT`, `ENCRYPTION_KEY`, `NEXTAUTH_SECRET`, `LANGFUSE_INIT_PROJECT_PUBLIC_KEY`, `LANGFUSE_INIT_PROJECT_SECRET_KEY`, `LANGFUSE_INIT_USER_PASSWORD` |
| Origen de los valores locales | `terraform/envs/dev/secrets.auto.tfvars` (no versionado); el ejemplo lleva marcadores `CHANGE_ME` |
| `ExternalSecret` de Langfuse | `langfuse-credentials`: `dataFrom.extract` del JSON completo |
| `ExternalSecret` del backend | `backend-langfuse`: solo `SHOPLAB_CHAT_TRACING_PUBLIC_KEY` y `SHOPLAB_CHAT_TRACING_SECRET_KEY`, con `property` |
| Config del backend (`backend-config`, overlay `dev`) | `SHOPLAB_CHAT_TRACING_ENABLED=true`, `SHOPLAB_CHAT_TRACING_ENDPOINT=http://langfuse-web:3000/api/public/otel`, `SHOPLAB_CHAT_TRACING_ENVIRONMENT=dev` |
| Servicios | `langfuse-web` (3000), `langfuse-worker`, `langfuse-postgres`, `langfuse-clickhouse`, `langfuse-redis`, `langfuse-minio`; todos `ClusterIP`, sin ingress |
| Almacenamiento | `volumeClaimTemplates` en `local-path` por almacén |
| Acceso a la interfaz | `kubectl -n shoplab-dev port-forward svc/langfuse-web 3001:3000` |
| Recursos | requests mínimos y límites por componente en el overlay `dev` (valores medidos en la verificación S-4) |
| Imágenes | las mismas versiones fijadas que en compose |
