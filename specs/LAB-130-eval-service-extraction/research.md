# Research: LAB-130

Hallazgos del 2026-11-25/26 leyendo `shoplab-helpdesk`, las specs/ADR de LAB-59 y LAB-129 y los repositorios hermanos
(`shoplab-infra`, `shoplab-gateway`, `shoplab-contracts`, `shoplab-eval`, `shoplab-chat-service`,
`shoplab-retrieval-service`, `shoplab-service-template`). Todos los puntos inicialmente marcados por verificar han
quedado comprobados.

## D-1 Alcance real del módulo

- **Decisión**: mover todo `backend/src/main/java/com/shoplab/helpdesk/eval` (api, application, config, domain,
  infrastructure) y `db/migration/eval` (V1 baseline, V2 historial), con sus pruebas y `src/test/resources/eval/queries`.
- **Hallazgo**: `eval` publica tres controladores, no dos: `/api/eval/runs` (guardar/leer, con token),
  `/api/eval/settings` (con token) y `/api/eval/history/**` (lectura para `admin-eval`, sin token, ADR-0029). Jira solo
  nombra los dos primeros; el gateway mueve `/api/eval/**` completo, así que el historial viaja también (spec, Assumptions).
- **No existe** `/api/eval/cases/promote` ni eventos Kafka, aunque `docs/mapa-servicios.md` los describe: fuera de
  alcance (FR-028) y se corrige el documento.

## D-2 Base de datos propia

- **Decisión**: base `eval` con rol `eval`, en el mismo servidor, **sin `pgvector`**, como `chat` (ADR-0034).
  `infra/postgres/eval-db-init.sh` idempotente (copia de `chat-db-init.sh`: crea rol y base, `REVOKE CONNECT` a `PUBLIC`
  en ambas bases) y servicio `eval-db-init` en el compose.
- **Esquema**: se conserva el esquema `eval` dentro de la base `eval` y las migraciones idénticas (Flyway con
  `ModuleSchema("eval")`, como hoy). Cambiarlo obligaría a reescribir V1/V2 y `flyway_schema_history`.
- **Alternativa descartada**: pasar a esquema `public`. Rompe la verificación «contenido idéntico» (FR-004).

## D-3 Migración de datos

- **Decisión**: `pg_dump --schema=eval` + `pg_restore` bajo `SET ROLE eval` en una transacción
  (`scripts/migrate-eval.{sh,ps1}` sobre `infra/postgres/eval-migrate.sh`), modelado en `migrate-chat`.
- **Verificación** (`verify-eval-migration`): recuentos de `eval_run`, `eval_case`, `eval_result` y
  `flyway_schema_history`; suma MD5 por tabla; y `last_value >= max(id)` en las tres secuencias
  `GENERATED ALWAYS AS IDENTITY` (FR-006).
- **Riesgo propio de `eval`**: columnas `jsonb`; se compara con `md5(row::text)` ordenado por `id` y se confirma que la
  representación de `jsonb` no cambia entre bases del mismo servidor (mismo motor ⇒ idéntica).
- **Origen intacto**: el esquema `eval` de `shoplab` no se borra aquí (FR-025).

## D-4 Cliente de `chat-service` desde el contrato

- **Hallazgo**: el monolito ya tiene `HttpAssistantSettingsClient` sobre `AssistantSettingsApi` (interfaz reactiva
  generada por `shoplab-contracts` 1.4.0), con modo `in-process`/`http`, timeout `5s` (`SHOPLAB_CHAT_SETTINGS_CLIENT_TIMEOUT`)
  y base URL (`SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL`, hoy por defecto `http://localhost:8083`). `AssistantSettingsApi`
  es el nombre del cliente generado, no código del monolito.
- **Decisión**: en `eval-service` se conserva `AssistantSettingsPort` y un adaptador HTTP cuyo cliente sale **del
  OpenAPI publicado por `chat-service` en `shoplab-contracts`** (generado, igual que lo hace `chat` para `retrieval`).
  La frase «en lugar de `AssistantSettingsApi`» de la historia se cumple eliminando el modo `in-process`, el adaptador de
  `chat` del monolito y cualquier dependencia de código de `chat`; si el nombre generado coincide es irrelevante: el
  criterio verificable es SC-005 (0 dependencias de código de `chat`).
- **Se conserva**: timeout acotado, propagación de `X-Request-Id`/`traceparent` y traducción de errores a
  `EvalStoreUnavailableException` → `SLHD_EVAL_00005` (503).
- **Verificado (T007, T008)**: `com.shoplab:shoplab-contracts:1.4.0` ya genera `com.shoplab.contracts.chat.client.AssistantSettingsApi`
  y `com.shoplab.contracts.chat.model.*`, compila y deserializa respuestas con `version: null` bajo Spring Boot 4.1.2, y
  se resuelve junto con `com.shoplab:shoplab-chat-service:0.1.0:stubs` desde `shoplab-eval-service` con `.mvn/ci-settings.xml`
  y `GITHUB_TOKEN`.

## D-5 Contratos y stubs

- **Decisión**: OpenAPI de `eval-service` en `shoplab-contracts` (`openapi/eval/v1/eval-api.yaml`, ya publicado en
  `1.4.0` con las 6 rutas y los modelos `com.shoplab.contracts.eval.model`). El servicio verifica el contrato como
  productor (Spring Cloud Contract, 5 contratos en `src/test/resources/contracts/eval/`) y publica stubs
  `com.shoplab:shoplab-eval-service:<v>:stubs` (flujo `publish-stubs.yml`).
- **Hallazgo**: el monolito tenía `EvalChatContractConsumerTest` consumiendo los stubs de `chat-service`. Pasa tal cual
  al repo nuevo (`com.shoplab:shoplab-chat-service:0.1.0:stubs`); es la «prueba de contrato eval → chat» del criterio.
- **Cuidado**: el `publish-stubs.yml` y `images.yml` de este repo filtran por `backend/**`; se retiran en la fase D.

## D-6 Herramienta Python (`shoplab-eval`, LAB-109)

- **Verificado (T004)**: `EvalStoreClient` (`src/shoplab_eval/store_client.py`) recibe `base_url` y `X-Eval-Token`
  (`SHOPLAB_EVAL_PERSISTENCE_TOKEN`) y llama exclusivamente a `/api/eval/settings`, `/api/eval/runs/{id}` y
  `POST /api/eval/runs` a través del gateway; no requiere ningún cambio de código y ni siquiera cambia la URL cuando
  entra por el gateway.
- **Verificación**: ejecutar una corrida real tras el corte y abrirla en `admin-eval` (FR-018).

## D-7 Corte, gateway y despliegue

- **Verificado (T003, T005)**: `shoplab-gateway` (`src/main/resources/application.yml`) define `shoplab.gateway.routes.eval.prefix: /api/eval`
  y reenvía todas las cabeceras (`X-Eval-Token`, `X-Request-Id`, `traceparent`). Un commit en
  `shoplab-infra/apps/gateway/overlays/dev/kustomization.yaml` añade `SHOPLAB_GATEWAY_ROUTES_EVAL_URI=http://eval-service:8080`.
  Revertir ese commit devuelve la ruta al monolito mientras exista.
- **`apps/eval-service` (verificado T003, implementado PR #48)**: existía como esqueleto de LAB-104 con `v1.0.0`. Se ha
  completado siguiendo `apps/chat-service`: `eval-service-config` (`POSTGRES_URL`, `POSTGRES_USER`,
  `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL=http://chat-service:8080`, `SHOPLAB_CHAT_SETTINGS_CLIENT_TIMEOUT=5s`,
  `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE`), `ExternalSecret` `eval-service-db` (`/shoplab/dev/eval-db-password`) y
  `eval-service-eval` (`/shoplab/dev/eval` `EVAL_PERSISTENCE_TOKEN`), `ServiceMonitor`, `NetworkPolicy` y `newTag: v0.1.0`.
- **Sin clave de LiteLLM propia**: `eval` no llama a modelos (a diferencia de `chat` y `retrieval`). El juez de LLM vive
  en la herramienta Python, no en el servicio. No se añade `LITELLM_KEY_EVAL` al servicio.
- **Ventana de corte**: en dev el token vacío ya deja el guardado en 403; el mismo mecanismo (vaciar el token del
  monolito) suspende el guardado sin tocar código.

## D-8 Retiro de `backend/`

- **Decisión**: fase D retira `backend/` completo. Inventario de lo que lo referencia **en este repo**:
  - `.github/workflows/ci.yml` (filtro `backend`, jobs del backend), `images.yml` (build-scan y publicación de la imagen),
    `publish-stubs.yml` (rutas `backend/**`), `release-please` (configuración de paquetes) y Qodana/Trivy.
  - `infra/docker-compose.yml` (servicio backend; el 8081 que Prometheus recoge), `infra/prometheus`, variables
    `LITELLM_KEY_BACKEND`.
  - `docs/modulos/**` y `docs/*.md` que hablan del monolito; `README.md`; `.gitignore`/`.dockerignore`.
  - La constitución (ver D-9) y `.ai`/`GEMINI.md` si citan `backend/`.
- **Fuera de este repo (verificado T003)**: `shoplab-infra/k8s/base/backend/` y su entrada en
  `shoplab-infra/k8s/base/kustomization.yaml` + `k8s/overlays/dev/patches/resources.yaml`, además de `GATEWAY_MONOLITH_URI`
  en `apps/gateway/overlays/dev/kustomization.yaml`.
- **No se toca**: datos de Olist ni `DROP SCHEMA` (ADR-0034, FR-025).
- **Orden**: el retiro va en un PR aparte, después de verificar la fase C, para que el monolito siga disponible como
  reversión durante la verificación.

## D-9 Gobernanza: constitución y numeración de ADR

- La constitución v1.0.2 fija `backend/` como Spring Boot/Modulith. Retirarlo es una desviación duradera: se enmienda
  (PATCH: la restricción de stack pasa a describir los servicios por repositorio) en el PR de la fase D y se anota en
  su informe de impacto. Principio I («Monolito modular primero») sigue válido para F0-F3 y no se redefine.
- **ADR**: el último es `0034`; el nuevo es **`0035-extraccion-de-eval-service.md`** y se enlaza desde `docs/adr/README.md`.
  Contenido: base propia sin `pgvector`, migración por volcado, corte por configuración, retiro de `backend/`,
  reversión y conservación del esquema antiguo.

## D-10 Línea base y paridad

- **Decisión**: antes de migrar, congelar en `specs/LAB-130-eval-service-extraction/baseline/` la salida del monolito
  para un conjunto de ejecuciones representativas: `GET /api/eval/history/runs`, `/runs/{id}` y `/compare` (ids fijos),
  más los recuentos y MD5 del esquema. Tras el corte se repite y se compara byte a byte (salvo `traceUrl` si cambia
  la plantilla).
- **Reutilizable**: `EvalHistoryEquivalenceTests` y las consultas `src/test/resources/eval/queries/*.sql` ya prueban
  equivalencia; viajan al servicio y se ejecutan en su CI.

## D-11 Entorno local

- **Decisión**: en el compose se añaden `eval-db-init` y `eval-service` (puerto de depuración libre, p. ej. 8084) y se
  quita `backend` en la fase D; `scripts/clone-all.ps1` ya clona los repos hermanos: añadir `shoplab-eval-service` si no
  está. `infra/.env.example` suma `EVAL_DB_PASSWORD` y `SHOPLAB_EVAL_PERSISTENCE_TOKEN`.
