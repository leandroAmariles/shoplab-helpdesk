# ADR-0034: Extracción de `chat-service`

- Estado: aceptado
- Fecha: 2026-11-25

## Contexto

LAB-59 extrae el orquestador conversacional (`chat`) del monolito a su propio repositorio, `shoplab-chat-service`
([ADR-0031](0031-extraccion-temprana-de-servicios.md)), detrás del gateway estrangulador
([ADR-0032](0032-gateway-estrangulador.md)) y consumiendo `retrieval-service` por HTTP
([ADR-0033](0033-extraccion-de-retrieval-service.md)). Hay que decidir dónde viven sus datos y su clave del gateway de
modelos, cómo se evita romper al módulo `eval` (que todavía vive en `backend/` hasta LAB-130), cómo se hace el corte y
qué ocurre con los módulos vacíos (`orders`, `catalog`, `tickets`) y con el retiro definitivo del monolito.

## Decisión

- **Base propia en el mismo servidor, sin `pgvector`.** Base `chat` con rol dedicado `chat` (`REVOKE CONNECT` a `PUBLIC`
  en ambas bases), aprovisionada de forma idempotente por `infra/postgres/chat-db-init.sh` (`chat-db-init`).
- **Migración por volcado y comprobación de secuencias `IDENTITY`.** `pg_dump --schema=chat` y `pg_restore` bajo
  `SET ROLE chat` en una sola transacción (`scripts/migrate-chat.{sh,ps1}`), verificando recuentos, sumas MD5 de
  `conversation_turn`, `feedback` y `flyway_schema_history`, además de `last_value >= max(id)` en ambas secuencias
  `GENERATED ALWAYS AS IDENTITY` (`scripts/verify-chat-migration.{sh,ps1}`).
- **Clave virtual propia en LiteLLM (`chat-<entorno>`).** `LITELLM_KEY_CHAT` con acceso a `[chat, chat-fallback]` (y
  `chat-cloud` cuando exista clave de proveedor), presupuesto independiente (`5.00/1d`), aislando el consumo de chat
  del resto de servicios ([ADR-0023](0023-gateway-de-modelos-litellm.md)).
- **`eval` consulta `GET /api/assistant/settings` por HTTP.** `shoplab-contracts` `1.4.0` genera la interfaz reactiva
  `AssistantSettingsApi`; `shoplab-chat-service` publica los stubs `com.shoplab:shoplab-chat-service:<version>:stubs`;
  `eval` endurece `HttpAssistantSettingsClient` con timeout acotado (`5s`), propagación de `X-Request-Id`/`traceparent`
  y traducción de errores a `EvalStoreUnavailableException`.
- **Corte en dos releases (expand-and-contract).** Release A (`v0.31.0`) despliega el cliente HTTP detrás de
  `shoplab.chat.settings.client.mode=http` (por defecto `in-process`). El corte es un solo commit en `shoplab-infra`
  (rutas `chat` y `assistant` del gateway hacia `http://chat-service:8080` y `mode=http` en `backend`). Release B retira
  `chat`, `orders`, `catalog` y `tickets` de `backend/`.
- **Retiro definitivo del monolito diferido a LAB-130 y conservación de Olist.** Dado que LAB-59 va antes de LAB-130
  (`eval-service`), `backend/` queda reducido únicamente a `eval` + `shared` hasta LAB-130. Retirar `orders`, `catalog`
  y `tickets` elimina únicamente los paquetes Java y archivos de migración de `backend/`, sin ejecutar `DROP SCHEMA`,
  preservando intactos los datos de Olist en PostgreSQL para F3/F4.

## Consecuencias

- `chat-service` se construye, publica, firma con Cosign y despliega de forma totalmente independiente del monolito,
  conservando sin cambios los códigos `SLHD_CHAT_*`, el streaming SSE, las citas RAG, la memoria, el feedback, los
  prompts versionados y las trazas OTLP de Langfuse.
- La separación de la base `chat` es de credenciales en el mismo servidor PostgreSQL compartido por local y `dev`.
- **Volver atrás.** Antes de Release B basta con revertir el commit de corte en `shoplab-infra`; los turnos y feedbacks
  escritos en `chat-service` durante la ventana no vuelven solos al esquema antiguo.
- El esquema `chat` antiguo en `shoplab` queda sin uso y su borrado (`DROP SCHEMA chat CASCADE` con copia previa) es un
  paso manual documentado en [F5-mover-rutas-al-gateway.md](../fases/F5-mover-rutas-al-gateway.md).
- `backend/` queda con superficie mínima (`eval` + `shared`, sin Spring AI ni OpenTelemetry), allanando la extracción
  final de `eval-service` y el retiro completo de `backend/` en LAB-130.
