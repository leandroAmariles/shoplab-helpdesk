# Phase 0 Research: LAB-59 Extraer chat-service del monolito

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-25

Este documento resuelve las decisiones técnicas abiertas en `spec.md` (*Notes for Planning*) y fija el diseño base
reutilizando lo aprendido en la extracción de `retrieval-service` (LAB-129, ADR-0033).

---

## D-01: Paquete base, estructura y plantilla del servicio

- **Decisión**: Generar `shoplab-chat-service` desde `shoplab-service-template` (ya publicado y corregido en LAB-129),
  con paquete base `com.shoplab.chat`, manteniendo la arquitectura hexagonal (`domain/`, `application/`, `infrastructure/`,
  `config/`) y la copia mínima de soporte técnico (`shared/`: errores `SLHD_CHAT_*`, `GlobalExceptionHandler`,
  `RequestIdFilter`, `ReactorContextConfig`, `BlockingCalls`, `DatabaseConfig`).
- **Racional**: Cumple ADR-0031 (cero librerías Maven comunes de dominio) y espeja exactamente `shoplab-retrieval-service`.
  Las migraciones viajan a `src/main/resources/db/migration/chat/` (`V1__baseline.sql`, `V2__conversation_turns.sql`,
  `V3__answer_feedback.sql`) y los prompts por defecto a `src/main/resources/prompts/*.txt`.
- **Alternativa descartada**: Reemplazar `SLHD_CHAT_*` por códigos nuevos o envolver respuestas en `ResponseDto<T>` de
  la plantilla. Se descarta porque rompería al frontend Angular, a `shoplab-eval` y a las pruebas existentes (FR-003).

## D-02: Base de datos propia y migración de `chat`

- **Decisión**: Crear una base de datos `chat` y un rol dedicado `chat` en el mismo servidor PostgreSQL de `dev` y local
  (mismo patrón que `retrieval` en ADR-0033), mediante un script idempotente `infra/postgres/chat-db-init.sh` ejecutado
  por un servicio de un solo disparo `chat-db-init` en el compose. A diferencia de `retrieval`, **`chat` no requiere
  la extensión `pgvector`**.
- **Migración**: `pg_dump --schema=chat --format=custom` sobre `shoplab` y `pg_restore --no-owner --no-privileges`
  bajo `SET ROLE chat` en `chat` dentro de una sola transacción (`infra/postgres/chat-migrate.sh` + wrappers
  `scripts/migrate-chat.{sh,ps1}`).
- **Secuencias de identidad**: `conversation_turn.id` y `feedback.id` usan `bigint GENERATED ALWAYS AS IDENTITY`.
  `pg_dump`/`pg_restore` incluye `SELECT pg_catalog.setval(...)`, y el verificador (`infra/postgres/chat-verify.sh` +
  `scripts/verify-chat-migration.{sh,ps1}`) comprobará además de los recuentos y sumas MD5 de `conversation_turn`,
  `feedback` y `flyway_schema_history`, que `last_value >= COALESCE(MAX(id), 0)` en ambas secuencias.
- **Borrado del esquema antiguo**: Igual que LAB-129, el esquema `chat` en `shoplab` se conserva tras el corte y su
  borrado (`DROP SCHEMA chat CASCADE` con copia previa) es un paso manual documentado en `docs/fases/F5-mover-rutas-al-gateway.md`.

## D-03: Clave virtual de LiteLLM dedicada para `chat-service`

- **Decisión**: Añadir una clave virtual `chat-${GATEWAY_ENVIRONMENT}` (`LITELLM_KEY_CHAT`, consumer `chat`) en
  `infra/litellm/keys.yaml` con acceso a `[chat, chat-fallback]` y `models_when: CLOUD_PROVIDER_API_KEY -> [chat-cloud]`,
  con presupuesto propio (`5.00 USD / 1d`, igual que el actual `backend`).
- **Transición**: Durante la fase en paralelo (antes de retirar `chat` del monolito) conviven `backend-${env}` y
  `chat-${env}` para permitir reversión inmediata. Tras retirar `chat` de `backend/` (Release B), `backend` ya no
  necesita llamar a LiteLLM (solo contiene `eval`), por lo que en `backend` se eliminan `SPRING_AI_OPENAI_*` y la clave
  `backend` puede reducirse a `models: []` (o retirarse en LAB-130).
- **Racional**: Aislamiento de presupuesto y trazabilidad de consumo por servicio (LAB-84, ADR-0023).

## D-04: Integración `chat-service` → `retrieval-service`

- **Decisión**: Mover `HttpKnowledgeSearchClient`, `RetrievalClientProperties`, `RetrievalClientConfig` y
  `ChatRetrievalContractConsumerTest` directamente a `shoplab-chat-service` sin cambiar el contrato. `chat-service`
  declara las dependencias a `com.shoplab:shoplab-contracts` (`1.3.0` o superior) y a los stubs
  `com.shoplab:shoplab-retrieval-service:<version>:stubs` en GitHub Packages.
- **Racional**: Esta integración ya fue validada y estabilizada en LAB-129 (timeout 15 s, propagación de `X-Request-Id`
  y `traceparent`, traducción a `SLHD_CHAT_00004`). Reutilizarla intacta minimiza riesgo.

## D-05: Contratos OpenAPI y stubs `chat-service` → `eval`

- **Decisión**:
  1. En `shoplab-contracts`: publicar la versión siguiente (p. ej. `1.4.0`) generando la interfaz HTTP reactiva para
     `GET /api/assistant/settings` (mismo mecanismo que `KnowledgeSearchApi` en LAB-129) y asegurando que el OpenAPI de
     `chat` documenta `POST /api/chat`, `GET /api/assistant/settings` y `/api/chat/feedback/**`.
  2. En `shoplab-chat-service`: mover `src/test/resources/contracts/chat/shouldReturnAssistantSettings.yml` y
     `ChatContractBase` desde `backend/`, configurar `spring-cloud-contract-maven-plugin` (`WEBTESTCLIENT`) y publicar
     `com.shoplab:shoplab-chat-service:<version>:stubs` en GitHub Packages vía `publish-stubs.yml`.
  3. En `backend/` (`eval`): endurecer `HttpAssistantSettingsClient` para usar el cliente generado de `shoplab-contracts`,
     timeout configurable (`shoplab.chat.settings.client.timeout`, por defecto `5s`), propagación de `X-Request-Id` y
     `traceparent`, y traducción de errores de red/timeout a una excepción tipada controlada (sin romper
     `EvalSettingsController`). Actualizar `EvalChatContractConsumerTest` para consumir
     `com.shoplab:shoplab-chat-service:<version>:stubs` en lugar de `com.shoplab:helpdesk-backend`.
- **Alternativa descartada**: Dejar `HttpAssistantSettingsClient` con DTOs manuales sin timeout. Se descarta porque
  incumple FR-012 y deja un cliente sin acotar en un servicio reactivo.

## D-06: Despliegue en `dev` (`shoplab-infra`) y configuración del gateway

- **Decisión**:
  - `shoplab-infra/apps/chat-service/` ya existe como esqueleto desde LAB-104 con `v1.0.0` y solo variables de DB. Se
    completa con: `POSTGRES_URL`/`USER`/`PASSWORD` (desde `ExternalSecret` `chat-service-db-secret`),
    `SPRING_AI_OPENAI_BASE_URL` y `SPRING_AI_OPENAI_API_KEY` (LiteLLM `chat-dev`), `SHOPLAB_RETRIEVAL_CLIENT_BASE_URL`
    (`http://retrieval-service:8080`), variables de `SHOPLAB_CHAT_PROMPTS_*` y `SHOPLAB_CHAT_TRACING_*` (Langfuse +
    secretos), y `newTag` alineado con la primera release funcional (`v0.1.0` / `v0.2.0`).
  - Corte en el gateway (`apps/gateway/overlays/dev/kustomization.yaml`): añadir en un solo commit:
    - `SHOPLAB_GATEWAY_ROUTES_CHAT_URI=http://chat-service:8080`
    - `SHOPLAB_GATEWAY_ROUTES_ASSISTANT_URI=http://chat-service:8080`
    - y en el overlay de `backend`: `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` y
      `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL=http://chat-service:8080`.
  - **Streaming SSE**: El gateway ya soporta SSE sobre `/api/chat` (120 s `responseTimeout`; ingress `dev` 60 s sin
    datos). No se requiere cambio de código en `shoplab-gateway` ni en `shoplab-frontend`.

## D-07: Retirada de módulos en `backend/` y estado transitorio hasta LAB-130

- **Decisión**: Estrategia *expand-and-contract* en dos releases del monolito (igual que LAB-129):
  - **Release A (expansión)**: `backend/` conserva el módulo `chat` intacto, actualiza `HttpAssistantSettingsClient`
    (cliente generado + timeout + stubs de `chat-service`) detrás de `shoplab.chat.settings.client.mode=http` (por
    defecto sigue `in-process`). Se despliega sin cambio de comportamiento.
  - **Corte**: Un solo commit en `shoplab-infra` activa `chat` y `assistant` en el gateway hacia `chat-service` y
    `mode=http` en `backend`.
  - **Release B (contracción)**: Tras verificar métricas de LAB-29 y recorridos en `dev`, se retiran de `backend/`:
    1. Módulo `chat` completo (código, `db/migration/chat`, `prompts/*.txt`, propiedades `shoplab.chat.*`, dependencia
       `spring-ai-starter-model-openai`, dependencias OpenTelemetry/Langfuse de `chat`, stubs de `retrieval-service`,
       contrato productor `chat` y pruebas de `chat`).
    2. Módulos vacíos `orders`, `catalog` y `tickets` (paquetes Java, `*SchemaConfig.java`, `db/migration/{orders,catalog,tickets}`).
       Los esquemas y tablas Olist en PostgreSQL **no se tocan**.
    3. `ChatAssistantSettingsAdapter` y el switch `mode=in-process` en `eval` (queda solo `HttpAssistantSettingsClient`).
  - **Estado resultante de `backend/`**: Queda únicamente `eval` + `shared` mínimo + `ShopLabHelpdeskApplication`.
    Se actualiza `ModularityTests` y `ResponseCodeEnum` (retirando los códigos `SLHD_CHAT_*` no usados por `eval`).
    El retiro definitivo de `backend/` ocurre en **LAB-130** (`eval-service`).
- **ADR**: Registrar **ADR-0034: Extracción de `chat-service`** documentando base de datos propia, clave LiteLLM, corte
  en dos releases, transición HTTP de `eval`, conservación de datos Olist y retiro diferido de `backend/` a LAB-130.

## D-08: Evaluación de calidad (LAB-29) como puerta de paridad

- **Decisión**: Antes del corte, ejecutar `shoplab-eval` (calidad de respuesta LAB-29) contra el sistema actual y
  guardar el reporte base (métricas agregadas y detalle por pregunta) junto con el modelo y versiones de prompts.
  Tras el corte a `chat-service`, repetir la misma corrida sobre el mismo dataset golden, corpus, modelo y prompts.
- **Criterio de paridad**: A diferencia de LAB-28 (recuperación determinista), LAB-29 evalúa salidas generativas con
  juez LLM, por lo que pueden existir pequeñas variaciones estocásticas; la comparación exige **no degradación** frente
  a la línea base y revisión explícita de cualquier caída relevante por pregunta antes de pasar a Release B.
