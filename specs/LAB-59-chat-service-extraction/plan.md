# Implementation Plan: LAB-59 Extraer chat-service del monolito

**Branch**: `feature/LAB-59-chat-service-extraction` | **Date**: 2026-11-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/LAB-59-chat-service-extraction/spec.md`

## Summary

Extraer el módulo `chat` del monolito (`backend/`) a su propio repositorio `shoplab-chat-service` con base de datos
propia (`chat`), clave virtual propia en LiteLLM y despliegue independiente en `dev` y local, conservando sin cambios
funcionales el streaming SSE, las citas RAG contra `retrieval-service` (LAB-129), la memoria multi-turno, el feedback,
los prompts versionados y las trazas OTLP de Langfuse. Se aplica *expand-and-contract* en dos releases del monolito:
primero se despliega `chat-service` en paralelo, se migran los datos y se hace el corte de rutas (`/api/chat/**` y
`/api/assistant/**`) junto con el cambio de `eval` a HTTP en un solo commit de configuración; luego una segunda release
retira `chat` y los módulos vacíos `orders`, `catalog` y `tickets` (preservando los esquemas Olist en PostgreSQL),
dejando `backend/` solo con `eval` y `shared` hasta LAB-130. Decisiones en [research.md](./research.md).

## Technical Context

**Language/Version**: Java 21; Bash y PowerShell para scripts de provisión y migración

**Primary Dependencies**: Spring Boot 4.1.2 (WebFlux), Spring AI 2.0.2 (OpenAI/LiteLLM), OpenTelemetry SDK/OTLP
(Langfuse), Flyway, JDBC, Micrometer, Spring Cloud Contract 5.0.3, `shoplab-contracts` (`1.4.0`), Kustomize y Argo CD

**Storage**: PostgreSQL 16 (sin `pgvector`). Base `chat` con rol `chat` dedicado en el mismo servidor que `shoplab` y
`retrieval` (compartido por local y `dev`)

**Testing**: JUnit 5, Reactor Test (`StepVerifier`), Testcontainers, WireMock, BlockHound, Spring Cloud Contract
(consumidor de `retrieval-service` y productor para `eval`), ArchUnit, Checkstyle, PMD, JaCoCo ≥ 80 %; evaluación de
calidad de LAB-29 (`shoplab-eval`)

**Target Platform**: Docker Compose en local (`shoplab-helpdesk`); Kubernetes (`shoplab-dev`) en `dev` (`shoplab-infra`)

**Project Type**: Microservicio web reactivo (WebFlux + SSE) + actualización en cuatro repositorios

**Performance Goals**: Streaming SSE sin buferizado adicional; la consulta HTTP de `eval` a `GET /api/assistant/settings`
acotada por timeout (5 s por defecto) y sin bloquear el hilo reactivo

**Constraints**: Paridad de API (`POST /api/chat`, `GET /api/assistant/settings`, `/api/chat/feedback/**`), códigos
`SLHD_CHAT_*` y métricas de calidad LAB-29; reversión en < 10 min con un solo commit antes de Release B; conservación
intacta de esquemas Olist (`catalog`, `orders`, `tickets`) y del módulo `eval` hasta LAB-130; sin autenticación JWT

**Scale/Scope**: ~5.600 líneas de código de `chat` (3 migraciones Flyway, 4 prompts por defecto, 3 controladores REST);
1 réplica en `dev`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Antes de Fase 0 | Tras Fase 1 | Cómo se cumple |
|---|---|---|---|
| I. Monolito modular primero | PASS | PASS | Extracción temprana cubierta por ADR-0031; ADR-0034 registra esta extracción |
| II. Hexagonal | PASS | PASS | `chat-service` mantiene puertos y adaptadores; `eval` consulta `GET /api/assistant/settings` detrás de `AssistantSettingsPort` |
| III. Identificadores en inglés, errores tipados | PASS | PASS | Identificadores en inglés; `ResponseCodeEnum` (`SLHD_CHAT_*`) y `GlobalExceptionHandler` dedicados en `chat-service` |
| IV. Pruebas y cobertura ≥ 80 % | PASS | PASS | Todas las pruebas unitarias, WebFlux, contrato y Testcontainers de `chat` viajan al servicio; cobertura ≥ 80 % verificada en ambos repos |
| V. RAG guiado por evaluación | PASS | PASS | Línea base de LAB-29 antes del corte y comparación después sobre el mismo golden dataset y modelo (FR-020) |
| VI. Observabilidad y secretos | PASS | PASS | `X-Request-Id`, `traceparent`, trazas OTLP Langfuse y métricas Micrometer conservadas; secretos vía Secrets Manager / `.env` |
| VII. Simplicidad y decisiones documentadas | PASS | PASS | Sin Kafka ni doble escritura; corte en una ventana; retiro definitivo de `backend/` diferido ordenadamente a LAB-130; ADR-0034 |

Sin violaciones.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-59-chat-service-extraction/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── http-api.md
│   ├── eval-client.md
│   ├── configuration.md
│   └── data-migration.md
└── tasks.md             # lo crea /speckit-tasks
```

### Source Code (por repositorio)

```text
shoplab-chat-service/                   # nuevo, generado desde shoplab-service-template
├── src/main/java/com/shoplab/chat/
│   ├── application/ domain/ infrastructure/ config/   # movidos de backend/.../chat
│   └── shared/                                        # copia técnica mínima: errores SLHD_CHAT_*, RequestIdFilter, BlockingCalls
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/chat/                             # V1__baseline, V2__conversation_turns, V3__answer_feedback
│   └── prompts/                                       # chat-system, chat-context, chat-empty-context, chat-small-talk
├── src/test/java/com/shoplab/chat/                    # unit, WebFlux, Testcontainers, ChatContractBase, consumer retrieval
├── src/test/resources/contracts/chat/                 # shouldReturnAssistantSettings.yml
├── config/  qodana.yaml  Dockerfile  mvnw  .mvn
└── .github/workflows/{ci,release,release-please,publish-stubs}.yml

shoplab-contracts/
├── openapi/chat.yaml                                  # contrato de POST /api/chat, GET /api/assistant/settings, feedback
└── generators/java/                                   # interfaz HTTP generada para AssistantSettingsApi (1.4.0)

shoplab-infra/
├── apps/chat-service/                                 # completar base + overlay dev (DB, retrieval, LiteLLM, Langfuse, secretos)
├── apps/gateway/overlays/dev/kustomization.yaml       # rutas chat y assistant -> http://chat-service:8080
├── k8s/base/backend/  k8s/overlays/dev/               # modo http para eval -> chat-service; luego limpieza post-Release B
├── k8s/base/litellm/  terraform/                      # clave virtual chat-dev y secretos en Secrets Manager

shoplab-helpdesk/
├── backend/                                           # Release A: endurecer HttpAssistantSettingsClient; Release B: retirar chat, orders, catalog, tickets
├── infra/
│   ├── docker-compose.yml                             # chat-db-init, chat-service, LITELLM_KEY_CHAT, gateway routes
│   ├── litellm/keys.yaml                              # añadir clave virtual chat-${GATEWAY_ENVIRONMENT}
│   └── postgres/{chat-db-init.sh,chat-migrate.sh,chat-verify.sh}
├── scripts/{migrate-chat.*,verify-chat-migration.*,clone-all.ps1}
└── docs/{adr/0034-extraccion-de-chat-service.md,mapa-servicios.md,fases/F5-mover-rutas-al-gateway.md}
```

**Structure Decision**: un repositorio por servicio (ADR-0031), con paquete base `com.shoplab.chat`. Los scripts de
provisión y migración viven en `shoplab-helpdesk` junto al compose y al servidor PostgreSQL compartido por local y `dev`.

## Fases de entrega

Cada fase cierra con una puerta verificable antes de avanzar a la siguiente.

| Fase | Contenido | Repos | Historia | Puerta para seguir |
|---|---|---|---|---|
| A. Contratos | Verificar/completar OpenAPI de `chat` y generar interfaz HTTP `AssistantSettingsApi` en `shoplab-contracts` (`1.4.0`) | contracts | US1, US3 | Artefacto `1.4.0` publicado; la interfaz compila y deserializa en Boot 4 |
| B. Servicio | Crear `shoplab-chat-service` desde `shoplab-service-template`; mover código, migraciones, prompts, pruebas, consumidor de `retrieval-service` y contrato productor; primera release funcional | chat-service | US1 | CI en verde (cobertura ≥ 80 %, ArchUnit, BlockHound), imagen firmada `:vX.Y.Z` y stubs `com.shoplab:shoplab-chat-service:stubs` publicados |
| C. Entornos | Scripts `chat-db-init`/`migrate`/`verify`, clave virtual `chat` en LiteLLM, `apps/chat-service` y secretos en `shoplab-infra`, servicio en compose local y `clone-all` | infra, helpdesk | US1, US2 | `chat-service` sano en local y en `dev` (sin tráfico aún), conectado a `retrieval-service`, LiteLLM y Langfuse |
| D. Monolito, Release A | Endurecer `HttpAssistantSettingsClient` en `eval` (interfaz generada, timeout, errores, correlación) y apuntar `EvalChatContractConsumerTest` a stubs de `shoplab-chat-service`; por defecto sigue `in-process` | helpdesk | US3 | Release A desplegada en `dev` sin cambio de comportamiento |
| E. Corte | Tomar línea base LAB-29; migrar esquema `chat` y verificar (`verify-chat-migration`); un commit en `shoplab-infra` mueve `chat` y `assistant` en el gateway y activa `mode=http` en `eval`; repetir LAB-29 y verificar trazas/feedback/SSE | infra, helpdesk | US2, US3 | Métricas LAB-29 sin degradación; SSE, citas, memoria, feedback y trazas Langfuse ok; reversión verificada por configuración |
| F. Retirada, Release B | Retirar `chat`, `orders`, `catalog`, `tickets` y `mode=in-process` de `backend/`; limpiar dependencias/propiedades sobrantes; ADR-0034 y actualización de docs (`mapa-servicios.md`, `F5-mover-rutas-al-gateway.md`) | helpdesk, infra | US3 | `backend/` verde solo con `eval` + `shared` (cobertura ≥ 80 %), esquemas Olist intactos, despliegue sano en `dev` |

El borrado del esquema `chat` antiguo en `shoplab` queda como paso manual documentado fuera de las releases (FR-009).

## Riesgos

| Riesgo | Efecto | Mitigación |
|---|---|---|
| Variabilidad estocástica del juez LLM en LAB-29 | Diferencias pequeñas entre línea base y post-corte pueden parecer regresión | Fijar mismo modelo, prompts y dataset golden; revisar diferencias caso a caso antes de Release B |
| Secuencias `IDENTITY` desalineadas tras restaurar `chat` | Primer turno o feedback nuevo falla por clave duplicada | `chat-verify.sh` valida `last_value >= MAX(id)` en `conversation_turn` y `feedback` antes del corte |
| Buffering o corte de SSE a través del gateway / ingress | Respuestas largas se cortan o llegan en bloque | Probar streaming incremental en local y `dev` en Fase E antes de retirar `chat` del monolito |
| Eliminación accidental de datos Olist al quitar `orders`/`catalog`/`tickets` | Se pierden tablas necesarias para F3/F4 | Retirar solo código Java y archivos de migración de `backend/`; nunca ejecutar `DROP SCHEMA`; verificar `\dt` tras Release B |
| Local y `dev` comparten servidor PostgreSQL | La migración afecta a ambos entornos | Copia previa de `shoplab`, migración única con verificación automática y esquema origen intacto |
| Conflicto de lectura de paquetes en GitHub Packages | `chat-service` o `backend` no descargan `shoplab-contracts` o stubs | Reutilizar la configuración de repositorios y credenciales ya probada en LAB-129 |

## Creación de repositorio y reparto de permisos

| Paso | Quién |
|---|---|
| Crear `shoplab-chat-service` desde `shoplab-service-template`, ajustar squash merge y `DEPLOY_APP_ID` | Agente (`gh`) |
| Cargar secretos `DEPLOY_APP_PRIVATE_KEY` y `RELEASE_PLEASE_TOKEN` en `shoplab-chat-service` | Mantenedor |
| Hacer público el paquete `chat-service` en GHCR (acción irreversible) | Mantenedor |
| Añadir secretos de `chat-service` (DB, LiteLLM `chat-dev`, Langfuse, override token) a `secrets.auto.tfvars` / Secrets Manager | Mantenedor (o agente si lo autoriza) |
| Fusionar el commit de corte en `shoplab-infra` | Mantenedor |

## Complexity Tracking

Sin violaciones que justificar.
