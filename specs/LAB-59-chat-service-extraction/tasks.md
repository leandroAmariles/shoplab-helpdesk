---
description: "Task list for LAB-59 chat-service extraction"
---

# Tasks: LAB-59 Extraer chat-service del monolito

**Input**: Design documents from `/specs/LAB-59-chat-service-extraction/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: obligatorias (constitución IV). Cada prueba nueva o adaptada se escribe antes del código y se verifica
fallando antes de implementar.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[USn]**: historia de `spec.md` a la que pertenece la tarea (`US1`, `US2`, `US3`)
- **MANUAL**: lo ejecuta la persona dueña de las cuentas. Los valores de secretos **nunca** se escriben en el chat, en
  archivos versionados ni en commits.
- **APROBACIÓN**: publica algo fuera de la máquina o afecta a datos (repositorio, release, merge, despliegue); requiere
  confirmación antes.

**Rutas**: las que empiezan por `shoplab-…/` son repositorios hermanos; el resto pertenecen a `shoplab-helpdesk`.
- `SVC` = `shoplab-chat-service/src/main/java/com/shoplab/chat`
- `SVCT` = `shoplab-chat-service/src/test/java/com/shoplab/chat`
- `MONO` = `backend/src/main/java/com/shoplab/helpdesk`
- `MONOT` = `backend/src/test/java/com/shoplab/helpdesk`

**Orden por dependencias**: `US3` se divide en dos releases alrededor del corte (`US2`) siguiendo el patrón
*expand-and-contract* de [plan.md](plan.md): Release A prepara el cliente HTTP en `eval`, luego se ejecuta el corte en
`US2`, y por último Release B retira `chat` y los módulos vacíos `orders`, `catalog` y `tickets` (conservando `eval` +
`shared` y los esquemas Olist hasta LAB-130).

---

## Phase 1: Setup

**Purpose**: Preparar ramas de trabajo y registro de evidencia en los repositorios implicados.

- [x] T001 Crear la rama `feature/LAB-59-chat-service-extraction` desde `origin/main` en `shoplab-contracts/` y `shoplab-infra/` (comprobar antes que ambos árboles locales estén limpios)
- [x] T002 [P] Crear `specs/LAB-59-chat-service-extraction/validation.md` con una sección por cada paso de [quickstart.md](quickstart.md) para registrar evidencia (línea base LAB-29, migración, corte, reversión y Release B)

---

## Phase 2: Foundational — Contratos OpenAPI y cliente generado (Fase A del plan)

**Purpose**: Publicar el contrato de `chat` y la interfaz HTTP reactiva `AssistantSettingsApi` en `shoplab-contracts`
antes de construir el servicio y endurecer el cliente de `eval`.

**⚠️ CRITICAL**: T003–T006 bloquean la compilación del cliente generado en `eval` (Fase D) y la verificación OpenAPI
de `shoplab-chat-service` (Fase B).

- [x] T003 [US1] Verificar y completar la especificación OpenAPI de `chat` en `shoplab-contracts/` cubriendo `POST /api/chat` (SSE), `GET /api/assistant/settings` y `/api/chat/feedback/**` (`PUT /{turnId}`, `GET`, `POST /{turnId}/promote`) según [http-api.md](contracts/http-api.md)
- [x] T004 [US3] Configurar `shoplab-contracts/generators/java/pom.xml` para generar la interfaz HTTP reactiva `com.shoplab.contracts.chat.client.AssistantSettingsApi` (`spring-http-interface`, mismo patrón que `KnowledgeSearchApi` en LAB-129) y sus modelos de respuesta
- [x] T005 [US3] Validar la compilación y deserialización de `AssistantSettingsApi` (incluyendo `version` nulo cuando `source=DEFAULT`) bajo Spring Boot 4.1.2 antes de publicar
- [x] T006 [US1] APROBACIÓN: fusionar el PR en `shoplab-contracts` y publicar la release `1.4.0` en GitHub Packages

**Checkpoint**: `com.shoplab:shoplab-contracts:1.4.0` disponible en GitHub Packages.

---

## Phase 3: User Story 1 - El chat vive en su propio servicio con sus datos, prompts, trazas y modelos (Priority: P1) 🎯 MVP

**Goal**: Crear `shoplab-chat-service` desde la plantilla, migrar código, migraciones, prompts, pruebas y contratos,
publicar imagen y stubs, y dejarlo sano en local y `dev` sin tráfico.

**Independent Test**: Levantar `shoplab-chat-service` con una copia migrada del esquema `chat` en paralelo al monolito;
verificar `POST /api/chat` (SSE, cortesías, citas, memoria), `GET /api/assistant/settings`, `/api/chat/feedback/**`,
trazas Langfuse y stubs publicados.

### Repositorio base y secretos

- [x] T007 [US1] APROBACIÓN: generar `leandroAmariles/shoplab-chat-service` público desde `shoplab-service-template` (`gh repo create --template`), clonarlo junto a los repos hermanos, fijar fusión por squash y actualizar el nombre del servicio en `shoplab-chat-service/pom.xml` y `shoplab-chat-service/.github/workflows/`
- [x] T008 [US1] Cargar la variable `DEPLOY_APP_ID` en `shoplab-chat-service` (mismo valor que en `shoplab-retrieval-service`)
- [x] T009 [US1] MANUAL: crear el token limitado a `shoplab-chat-service` (`Contents` y `Pull requests`: lectura/escritura) y cargarlo en `RELEASE_PLEASE_TOKEN`
- [x] T010 [US1] MANUAL: cargar `DEPLOY_APP_PRIVATE_KEY` en los secretos de `shoplab-chat-service`

### Código, migraciones, prompts y pruebas del servicio

- [x] T011 [US1] Copiar las pruebas de `MONOT/chat/` (incluyendo `ChatStreamContractTests`, `ChatRetrievalContractConsumerTest` y `ChatContractBase`) y `MONOT/support/SharedPostgres.java` a `SVCT/` adaptando el paquete base a `com.shoplab.chat` (el monolito las conserva hasta Release B)
- [x] T012 [P] [US1] Copiar a `SVC/shared/` el soporte técnico mínimo desde `MONO/shared/`: excepciones, `ResponseCodeEnum` solo con `SLHD_CHAT_00001`–`00015`, `SLHD_DBAS_*` y `SLHD_GENE_*`, `ErrorResponse`, `GlobalExceptionHandler`, `RequestIdFilter`, `BlockingCalls`, `ReactorContextConfig`, `DatabaseConfig`, `ModuleSchema`, `ModuleSchemaMigrator` y `GatewayHeaders`, con sus pruebas (sin envoltorio `ResponseDto`)
- [x] T013 [US1] Copiar `MONO/chat/{application,domain,infrastructure,config}` a `SVC/` (sin `api/`), configurar `shoplab-chat-service/pom.xml` con Spring Boot 4.1.2, Spring AI 2.0.2 (`spring-ai-starter-model-openai`), OpenTelemetry SDK/OTLP, JDBC, Flyway, `shoplab-contracts:1.4.0`, stubs `com.shoplab:shoplab-retrieval-service:<version>:stubs`, BlockHound, WireMock, Testcontainers, Checkstyle, PMD y JaCoCo ≥ 80 %
- [x] T014 [P] [US1] Copiar `backend/src/main/resources/db/migration/chat/V1`–`V3` **sin modificar su contenido** a `shoplab-chat-service/src/main/resources/db/migration/chat/` y `backend/src/main/resources/prompts/*.txt` a `shoplab-chat-service/src/main/resources/prompts/`
- [x] T015 [US1] Configurar `shoplab-chat-service/src/main/resources/application.yml` según [configuration.md](contracts/configuration.md) (datasource `chat`, LiteLLM, `shoplab.retrieval.client.*`, `shoplab.chat.*`, prompts, tracing, sondas `/actuator/health` y `/actuator/prometheus`)
- [x] T016 [US1] Mover `backend/src/test/resources/contracts/chat/shouldReturnAssistantSettings.yml` a `shoplab-chat-service/src/test/resources/contracts/chat/`, configurar `spring-cloud-contract-maven-plugin` (`WEBTESTCLIENT`) y `distributionManagement` en `shoplab-chat-service/pom.xml`, y añadir `shoplab-chat-service/.github/workflows/publish-stubs.yml`
- [x] T017 [US1] Ejecutar `./mvnw verify` en `shoplab-chat-service` (cobertura ≥ 80 %, ArchUnit, BlockHound, consumidor de `retrieval-service`, productor de `chat`, Checkstyle, PMD) y actualizar `shoplab-chat-service/README.md`
- [x] T018 [US1] APROBACIÓN: fusionar en `shoplab-chat-service`, publicar la primera release funcional (`v0.1.0`), comprobar imagen firmada en GHCR y stubs `com.shoplab:shoplab-chat-service:0.1.0:stubs` en GitHub Packages
- [ ] T019 [US1] MANUAL / APROBACIÓN: hacer **público** el paquete `chat-service` en GHCR (acción irreversible)

### Datos, clave LiteLLM, entorno local y despliegue en `dev` sin tráfico (Fase C del plan)

- [x] T020 [P] [US1] Crear `infra/postgres/chat-db-init.sh` idempotente (rol `chat`, base `chat` dueña `chat`, `REVOKE CONNECT … FROM PUBLIC`, sin `pgvector`) según [data-migration.md](contracts/data-migration.md)
- [x] T021 [US1] Crear `infra/postgres/chat-migrate.sh`, `infra/postgres/chat-verify.sh` y sus wrappers `scripts/migrate-chat.{sh,ps1}` y `scripts/verify-chat-migration.{sh,ps1}` validando recuentos, checksums MD5 de `conversation_turn`, `feedback`, `flyway_schema_history` y secuencias `GENERATED ALWAYS AS IDENTITY`
- [x] T022 [US1] Probar los scripts de T020–T021 contra un PostgreSQL desechable: copia correcta, segunda ejecución sin `--replace` rechazada con código 3, alteración de fila detectada por `verify`, inserción posterior en `conversation_turn` y `feedback` sin colisión de secuencia, y base origen intacta; anotar en `validation.md`
- [x] T023 [P] [US1] Añadir la clave virtual `chat-${GATEWAY_ENVIRONMENT}` (`LITELLM_KEY_CHAT`, modelos `[chat, chat-fallback]` y `chat-cloud` condicional, `5.00/1d`) a `infra/litellm/keys.yaml` y actualizar `infra/litellm/tests/test_bootstrap_keys.py`; ejecutar pruebas de `infra/litellm`
- [x] T024 [US1] Actualizar `infra/docker-compose.yml` añadiendo `chat-db-init`, `chat-service` (puerto host `${CHAT_PORT:-8083}:8080`, imagen fijada), `LITELLM_KEY_CHAT` en `litellm-bootstrap`, y actualizar `infra/.env.example` e `infra/docker-compose.override.yml.example`
- [x] T025 [P] [US1] Añadir `shoplab-chat-service` a `scripts/clone-all.ps1`, al objetivo `clone-all` de `Makefile` y al scrape de `infra/observability/prometheus/prometheus.yml`
- [x] T026 [US1] Completar `shoplab-infra/apps/chat-service/base/` y `overlays/dev/` (DB `chat`, `retrieval-service`, LiteLLM `chat-dev`, Langfuse prompts y tracing, secretos `ExternalSecret`, sondas y `newTag` alineado con T018) y añadir la clave `chat-dev` en `shoplab-infra/k8s/base/litellm/`
- [x] T027 [US1] MANUAL: añadir los secretos de `chat-service` (contraseña DB `chat`, `LITELLM_KEY_CHAT`, claves de Langfuse y `SHOPLAB_CHAT_PROMPTS_OVERRIDE_TOKEN`) a `secrets.auto.tfvars` / Secrets Manager
- [x] T028 [US1] APROBACIÓN: fusionar el PR de alta de `chat-service` en `shoplab-infra` (sin mover rutas del gateway aún) y verificar sincronización sana en `shoplab-dev` (anotar en `validation.md`)

**Checkpoint**: `chat-service` está sano en local y en `dev`, con su base, clave LiteLLM y conectividad a `retrieval-service` y Langfuse, aún sin tráfico de usuarios.

---

## Phase 4: User Story 3 (Parte 1) - Monolito Release A: preparar `eval` por HTTP (Fase D del plan)

**Goal**: Endurecer `HttpAssistantSettingsClient` en `eval` detrás de `shoplab.chat.settings.client.mode=http` (por
defecto sigue `in-process`) y probarlo contra los stubs publicados por `shoplab-chat-service`.

**Independent Test**: `mvn verify` en `backend/` pasa con `EvalChatContractConsumerTest` contra
`com.shoplab:shoplab-chat-service:stubs` y pruebas WireMock de timeout/503; Release A se despliega sin cambio de
comportamiento.

- [x] T029 [US3] Escribir/actualizar pruebas en `MONOT/eval/infrastructure/out/chat/`: actualizar `EvalChatContractConsumerTest` para consumir `com.shoplab:shoplab-chat-service:<version>:stubs` y añadir prueba WireMock que verifique timeout (`5s`), propagación de `X-Request-Id`/`traceparent` y traducción de fallos HTTP/timeout a excepción tipada según [eval-client.md](contracts/eval-client.md)
- [x] T030 [US3] Actualizar `backend/pom.xml` a `shoplab-contracts:1.4.0`, añadir el repositorio GitHub Packages y dependencia test de stubs `com.shoplab:shoplab-chat-service:<version>:stubs`
- [x] T031 [US3] Refactorizar `MONO/eval/infrastructure/out/chat/HttpAssistantSettingsClient.java` (y su configuración `WebClient` / propiedades `shoplab.chat.settings.client.{mode,base-url,timeout}`) para usar la interfaz generada `AssistantSettingsApi`, timeout acotado, propagación de cabeceras y traducción de errores con `onErrorMap` manteniendo `mode=in-process` por defecto
- [x] T032 [US3] Ejecutar `mvn verify -Dskip.srcclr=true --no-transfer-progress` en `backend/` (cobertura ≥ 80 %, Checkstyle, PMD)
- [x] T033 [US3] APROBACIÓN: fusionar y publicar Release A de `backend/` y verificar que despliega en `dev` sin cambiar el comportamiento actual

**Checkpoint**: El monolito sabe consultar `chat-service` por HTTP al activar `mode=http`, manteniendo el comportamiento en proceso por defecto.

---

## Phase 5: User Story 2 - Corte de rutas en el gateway y verificación de paridad (Fase E del plan)

**Goal**: Migrar los datos de `chat`, activar `chat-service` en el gateway (`/api/chat/**` y `/api/assistant/**`) y
`mode=http` en `eval` en un solo commit de configuración, validar calidad (LAB-29) y confirmar reversibilidad.

**Independent Test**: Recorrido completo en `dev` y local (SSE, citas, cortesías, memoria, feedback, promoción,
`GET /api/eval/settings`, trazas Langfuse) con 0 cambios en frontend ni `shoplab-eval`, y métricas LAB-29 sin
degradación.

- [ ] T034 [US2] Tomar la línea base de evaluación de calidad de respuesta (LAB-29) con `shoplab-eval` contra el monolito actual sobre el dataset golden vigente y guardar resumen, detalle por pregunta, modelo y versiones de prompts en `specs/LAB-59-chat-service-extraction/validation.md`
- [x] T035 [US2] APROBACIÓN: en ventana controlada, ejecutar copia previa de `shoplab`, correr `scripts/migrate-chat.{sh,ps1}` y validar con `scripts/verify-chat-migration.{sh,ps1}` (código 0); guardar evidencia en `validation.md`
- [x] T036 [US2] Preparar el commit único de corte en `shoplab-infra`: añadir `SHOPLAB_GATEWAY_ROUTES_CHAT_URI=http://chat-service:8080` y `SHOPLAB_GATEWAY_ROUTES_ASSISTANT_URI=http://chat-service:8080` en `apps/gateway/overlays/dev/kustomization.yaml`, y activar `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` + `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL=http://chat-service:8080` en el overlay `dev` de `backend`
- [x] T037 [US2] APROBACIÓN: fusionar el commit de corte en `shoplab-infra` y confirmar sincronización sana de `gateway`, `chat-service` y `backend` en `shoplab-dev`
- [ ] T038 [US2] Ejecutar la verificación funcional de [quickstart.md](quickstart.md) paso 5 en `dev` (cortesías, pregunta RAG con SSE y citas, repregunta multi-turno, feedback + promoción, `GET /api/eval/settings`, trazas en Langfuse, métricas en Prometheus/Grafana y logs del gateway mostrando `destination=http://chat-service:8080`)
- [ ] T039 [US2] Repetir la evaluación de calidad (LAB-29) sobre el mismo dataset golden, corpus, modelo y prompts que T034; comparar contra la línea base y documentar el resultado en `validation.md` (puerta obligatoria: sin degradación)
- [x] T040 [US2] Configurar en `infra/docker-compose.yml` el enrutamiento por defecto del gateway local (`chat` y `assistant` → `http://chat-service:8080`) y `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` en `backend`, y verificar `docker compose -f infra/docker-compose.yml up -d --wait` según [quickstart.md](quickstart.md) pasos 3–4
- [ ] T041 [US2] Verificar el procedimiento de reversión antes de Release B (comprobar que revertir el commit de configuración devuelve `/api/chat/**` y `/api/assistant/**` al monolito en < 10 min, o documentar ensayo controlado en `validation.md`)

**Checkpoint**: `chat-service` atiende todo el tráfico de chat, feedback y configuración del asistente en `dev` y local con paridad verificada.

---

## Phase 6: User Story 3 (Parte 2) - Monolito Release B: retirar `chat` y módulos vacíos (Fase F del plan)

**Goal**: Retirar `chat`, `orders`, `catalog`, `tickets` y el modo `in-process` de `backend/` sin alterar los esquemas
Olist en PostgreSQL, dejando `backend/` reducido a `eval` + `shared` hasta LAB-130.

**Independent Test**: `mvn verify` en `backend/` en verde con cobertura ≥ 80 %, sin referencias a `chat`, `orders`,
`catalog` ni `tickets`, y tablas Olist intactas en PostgreSQL.

- [x] T042 [US3] Retirar del monolito (`backend/`) el módulo `chat` completo: `MONO/chat/`, `MONOT/chat/`, `backend/src/main/resources/db/migration/chat/`, `backend/src/main/resources/prompts/`, `backend/src/test/resources/contracts/chat/`, y la configuración `spring-cloud-contract-maven-plugin` productor de `chat`
- [x] T043 [P] [US3] Retirar del monolito (`backend/`) los módulos vacíos `orders`, `catalog` y `tickets`: `MONO/{orders,catalog,tickets}/`, `MONOT/{orders,catalog,tickets}/` (si existen) y `backend/src/main/resources/db/migration/{orders,catalog,tickets}/`, **sin ejecutar ningún `DROP SCHEMA` en PostgreSQL**
- [x] T044 [US3] Eliminar `MONO/eval/infrastructure/out/chat/ChatAssistantSettingsAdapter.java`, retirar `@ConditionalOnProperty(mode=http)` de `HttpAssistantSettingsClient` para que sea el único adaptador de `AssistantSettingsPort`, y actualizar `MONOT/ModularityTests.java` para los módulos restantes (`eval`, `shared`)
- [x] T045 [US3] Limpiar `backend/pom.xml`, `backend/src/main/resources/application.yml` y `MONO/shared/exception/ResponseCodeEnum.java` retirando dependencias (`spring-ai-starter-model-openai`, OpenTelemetry/Langfuse, stubs de `retrieval-service`), propiedades `spring.ai.*` / `shoplab.chat.*` (salvo `shoplab.chat.settings.client.*`) / `shoplab.retrieval.*` y códigos `SLHD_CHAT_*` no usados por `eval`
- [x] T046 [US3] Limpiar en `infra/docker-compose.yml` y `shoplab-infra/k8s/base/backend/` las variables de entorno sobrantes de `backend` (`SPRING_AI_OPENAI_*`, `SHOPLAB_RETRIEVAL_CLIENT_*`, `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE`) y verificar que los esquemas `catalog` y `orders` conservan sus tablas y filas Olist intactas
- [x] T047 [US3] Ejecutar `mvn verify -Dskip.srcclr=true --no-transfer-progress` en `backend/` confirmando pruebas en verde, Checkstyle, PMD y cobertura JaCoCo ≥ 80 %; anotar resultado en `validation.md`
- [ ] T048 [US3] APROBACIÓN: fusionar y publicar Release B de `backend/` y verificar despliegue sano en `shoplab-dev` (`/api/eval/runs` y `/api/eval/settings` operativos)

**Checkpoint**: `backend/` contiene únicamente `eval` + `shared`, `chat-service` opera de forma totalmente independiente y los datos de Olist siguen intactos para F3/F4.

---

## Phase 7: Polish, ADR y Documentación

**Purpose**: Cerrar la trazabilidad arquitectónica y actualizar la documentación del ecosistema.

- [x] T049 [P] Redactar `docs/adr/0034-extraccion-de-chat-service.md` documentando base de datos propia `chat`, clave virtual de LiteLLM, corte en dos releases, transición HTTP de `eval`, conservación de Olist y retiro definitivo de `backend/` diferido a LAB-130; enlazarlo en `docs/adr/README.md`
- [x] T050 [P] Actualizar `docs/mapa-servicios.md` (ficha de `chat-service` marcada como extraída en LAB-59 con referencia a ADR-0034, rutas y esquema) y `docs/fases/F5-mover-rutas-al-gateway.md` (incluir el procedimiento manual de borrado del esquema `chat` antiguo en `shoplab` con copia previa)
- [x] T051 [P] Revisar `specs/LAB-59-chat-service-extraction/requests.http` si aplica o confirmar que los contratos HTTP no añadieron endpoints nuevos respecto a la API ya existente; validar `git status` y `git diff` limpios antes del PR final

---

## Dependencies & Execution Order

### Grafo de fases

```text
Phase 1 (Setup)
   └─▶ Phase 2 (Contratos: shoplab-contracts 1.4.0)
          └─▶ Phase 3 (US1: shoplab-chat-service + DB + LiteLLM + dev sin tráfico)
                 └─▶ Phase 4 (US3-A: Monolito Release A con eval HTTP behind flag)
                        └─▶ Phase 5 (US2: Línea base LAB-29 + Migración + Corte + LAB-29 post)
                               └─▶ Phase 6 (US3-B: Monolito Release B retira chat/orders/catalog/tickets)
                                      └─▶ Phase 7 (Polish: ADR-0034 + Docs)
```

### Oportunidades de paralelismo

- **En Phase 3**: T012 (soporte `shared/`) y T014 (migraciones + prompts) pueden hacerse en paralelo tras T011; T020 (`chat-db-init.sh`), T023 (`litellm/keys.yaml`) y T025 (`clone-all` + Prometheus) pueden correr en paralelo.
- **En Phase 6**: T042 (retirar `chat`) y T043 (retirar `orders`/`catalog`/`tickets`) tocan carpetas distintas y pueden ejecutarse en paralelo.
- **En Phase 7**: T049, T050 y T051 son independientes y paralelizables.

### Estrategia de entrega segura

1. **MVP verificable en Phase 3**: `shoplab-chat-service` publicado, firmado y sano en paralelo sin tocar el tráfico de usuarios.
2. **Corte reversible en Phase 5**: un único commit de configuración en `shoplab-infra`, validado con métricas LAB-29 antes de cualquier borrado.
3. **Limpieza controlada en Phase 6**: solo tras validar Phase 5 en `dev`, reduciendo `backend/` a `eval` + `shared` y dejando el retiro final del monolito listo para LAB-130.
