---
description: "Task list for LAB-129 retrieval-service extraction"
---

# Tasks: LAB-129 Extraer retrieval-service del monolito

**Input**: Design documents from `/specs/LAB-129-retrieval-service-extraction/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: obligatorias (constitución IV). Cada prueba se escribe antes del código y se ve fallar.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ir en paralelo (archivos distintos, sin dependencias pendientes)
- **[USn]**: historia de la spec
- **MANUAL**: lo hace la persona dueña de las cuentas. Los valores de secretos **no** se escriben en el chat, en
  archivos versionados ni en commits.
- **APROBACIÓN**: publica algo fuera de la máquina o afecta a datos (repositorio, tag, merge, release, despliegue); se
  confirma antes.

**Rutas**: las que empiezan por `shoplab-…/` son de repositorios hermanos; el resto son de `shoplab-helpdesk`.
`SVC` = `shoplab-retrieval-service/src/main/java/com/shoplab/retrieval`,
`SVCT` = `shoplab-retrieval-service/src/test/java/com/shoplab/retrieval`,
`MONO` = `backend/src/main/java/com/shoplab/helpdesk`,
`MONOT` = `backend/src/test/java/com/shoplab/helpdesk`,
`TPL` = `specs/LAB-103-reusable-workflows-service-template/shoplab-service-template`.

**El orden de las fases sigue las dependencias**, no solo la prioridad: US5 va antes del corte (plan, fase C) y US4 se
parte en dos releases alrededor de US3.

---

## Phase 1: Setup

- [X] T001 Crear la rama `feature/LAB-129-retrieval-service-extraction` desde `origin/main` en `shoplab-infra/`, `shoplab-ci/` y `shoplab-contracts/` (el árbol local de `shoplab-infra` está en una rama de LAB-104: comprobar que está limpio antes de cambiar)
- [X] T002 [P] Crear `specs/LAB-129-retrieval-service-extraction/validation.md` con una sección por paso de `quickstart.md` para guardar la evidencia

---

## Phase 2: Foundational (riesgos que condicionan el diseño)

**⚠️ CRITICAL**: si T003 o T004 fallan, detenerse y revisar research D-1 o D-6 antes de seguir.

- [X] T003 Probar la lectura entre repositorios de GitHub Packages con `GITHUB_TOKEN`: un workflow temporal en una rama de `shoplab-gateway/.github/workflows/` que resuelva `com.shoplab:shoplab-contracts:1.2.0`; anotar el resultado en research D-6 y borrar la rama. Si no puede, decidir entre dar acceso al paquete (MANUAL) o un token `read:packages` como secreto (MANUAL)
- [X] T004 Probar en `shoplab-contracts/generators/java/pom.xml` la generación de la interfaz `KnowledgeSearch` (`spring`, `spring-http-interface`, reactiva, modelos `com.shoplab.contracts.retrieval.model`, `spring-web` opcional) y que compila y deserializa una respuesta bajo Spring Boot 4.1.2 con un proyecto de prueba en el scratchpad; anotar el resultado en research D-1

**Checkpoint**: el cliente generado y el acceso a paquetes son viables.

---

## Phase 3: User Story 1 - La plataforma permite crear y desplegar un servicio (Priority: P1) 🎯 MVP

**Goal**: plantilla, workflows, `ci-gate` de infra y compose funcionando.

**Independent Test**: `shoplab-retrieval-service`, sin el módulo, publica y firma `:vX.Y.Z`; un PR que toca `k8s/`
obtiene `ci-gate` en verde; el compose arranca sin `.env`.

### Plantilla y workflows

- [X] T005 [US1] Corregir `TPL/pom.xml`: Spring Boot 4.1.2; quitar R2DBC, OAuth2 y logstash; añadir `starter-jdbc`, `starter-flyway`, repositorio y credenciales de `shoplab-contracts`, Testcontainers, puerta JaCoCo de 80 %, Checkstyle y PMD como en `backend/pom.xml`
- [X] T006 [US1] Quitar `TPL/src/main/java/com/shoplab/template/infrastructure/in/rest/config/SecurityConfig.java` y ajustar `TPL/src/main/resources/application.yml` (JDBC, sin `security`, sondas de salud, registro estructurado como `shoplab-gateway`) y las pruebas de `TPL/src/test/java/` hasta que `./mvnw verify` pase
- [X] T007 [P] [US1] Añadir a `TPL/` `mvnw`, `mvnw.cmd`, `.mvn/`, `config/checkstyle.xml`, `config/pmd-ruleset.xml`, `qodana.yaml`, `.dockerignore`, `.gitignore`, y sustituir `TPL/Dockerfile` por el de `shoplab-gateway/Dockerfile` (imágenes fijadas por digest, UID 10001)
- [X] T008 [P] [US1] Sustituir `TPL/.github/workflows/` por `ci.yml`, `release.yml` y `release-please.yml` tomados de `shoplab-gateway`, con `release.yml` publicando la imagen `:vX.Y.Z` antes de desplegar y `overlay: apps/<servicio>/overlays/dev`; ajustar `TPL/release-please-config.json` y `TPL/.release-please-manifest.json` para que la primera release sea `v0.1.0`
- [X] T009 [P] [US1] En `shoplab-ci/.github/workflows/publish-image.yml` verificar la firma sin distinguir mayúsculas (`(?i)` en `--certificate-identity-regexp`)
- [X] T010 [P] [US1] En `shoplab-ci/.github/workflows/java-service.yml` añadir `server-id: github-shoplab-contracts` con credenciales según T003 y subir Qodana a `JetBrains/qodana-action@v2026.2.2`
- [ ] T011 [US1] APROBACIÓN: fusionar el PR de `shoplab-ci` y mover el tag `v1` (afecta a todos los consumidores; anotar el commit anterior de `v1` para poder volver)
- [X] T012 [US1] APROBACIÓN: crear `leandroAmariles/shoplab-service-template` público con `gh repo create`, subir el contenido de `TPL/` y marcarlo como plantilla (`is_template`); actualizar la guía `docs/fases/F5-creacion-de-servicios-desde-plantilla.md`
- [X] T013 [US1] APROBACIÓN: generar `leandroAmariles/shoplab-retrieval-service` desde la plantilla (`gh repo create --template`, público), clonarlo junto a los demás, fijar fusión por squash y cambiar solo el nombre en `shoplab-retrieval-service/pom.xml` y `shoplab-retrieval-service/.github/workflows/`
- [X] T014 [US1] Cargar `DEPLOY_APP_ID` como variable de `shoplab-retrieval-service` (mismo valor que en `shoplab-gateway`)
- [ ] T015 [US1] MANUAL: crear un token limitado a `shoplab-retrieval-service` (Contents y Pull requests: lectura y escritura) y cargarlo con `gh secret set RELEASE_PLEASE_TOKEN -R leandroAmariles/shoplab-retrieval-service`
- [ ] T016 [US1] MANUAL: cargar la llave de la GitHub App con `gh secret set DEPLOY_APP_PRIVATE_KEY -R leandroAmariles/shoplab-retrieval-service < <ruta-al-pem>` (o autorizar al agente indicando el archivo)
- [ ] T017 [US1] APROBACIÓN: publicar `v0.1.0` de `shoplab-retrieval-service` y comprobar imagen `:v0.1.0` en GHCR con firma verificada (SC-008). El job de despliegue falla en esta release (se corrige en T040); anotar el resultado en `validation.md`
- [ ] T018 [US1] MANUAL / APROBACIÓN: hacer **público** el paquete `retrieval-service` en GitHub → Packages → Package settings. **No es reversible**

### `smoke` de shoplab-infra (research D-10)

- [X] T019 [US1] Reproducir el fallo en un PR de `shoplab-infra` y confirmar o descartar la hipótesis del `429` al bajar `registry:2`; anotar la evidencia en research D-10
- [X] T020 [US1] Intento 1: bajar `registry:2` desde `mirror.gcr.io`, fijarlo en `shoplab-infra/versions.env` y etiquetarlo antes de `up.sh` en `shoplab-infra/scripts/smoke.sh`; no subir `APPLY_TIMEOUT`
- [X] T021 [US1] Solo si T020 no da verde: intento 2 según el log, en `shoplab-infra/scripts/` o `shoplab-infra/.github/workflows/ci.yml`
- [X] T022 [US1] Dar el `smoke` por solucionado únicamente con dos ejecuciones seguidas en verde. Si no, quitar de `shoplab-infra/.github/workflows/ci.yml` el job `smoke`, su entrada en `needs` de `ci-gate`, la salida `smoke`, el paso «Decide whether smoke is needed» y `fetch-depth: 0`; borrar `shoplab-infra/scripts/is-dev-deploy-only.sh` y `shoplab-infra/scripts/tests/dev-deploy-only.test.sh` y su llamada en `shoplab-infra/Makefile` (`test-ci-scripts`); conservar `scripts/smoke.sh` y `make smoke`
- [ ] T023 [US1] Actualizar `shoplab-infra/README.md` y `shoplab-infra/docs/gitops.md` con el resultado de T022 y, APROBACIÓN, fusionar el PR con `ci-gate` en verde

### PostgreSQL del compose

- [X] T024 [P] [US1] En `infra/docker-compose.yml` poner `docker.io/postgres:17.7` por defecto en `litellm-postgres` y `langfuse-postgres`; revisar `.github/dependabot.yml` para ignorar saltos mayores de PostgreSQL en `infra/`
- [X] T025 [US1] Comprobar sin `infra/.env` que todos los PostgreSQL quedan sanos (perfil `llmops` incluido) con volúmenes nuevos y con volúmenes 17 existentes, sin borrar ningún volumen; anotar en `validation.md`

**Checkpoint**: la plataforma admite el servicio nuevo.

---

## Phase 4: User Story 2 - La base de conocimiento en su propio servicio (Priority: P1)

**Goal**: servicio con la misma API y su base de datos, desplegado sin tráfico.

**Independent Test**: quickstart pasos 3 y 4: datos migrados sin diferencias y mismas respuestas que el monolito.

### Código y pruebas

- [X] T026 [US2] Mover las pruebas de `MONOT/retrieval/` (menos `infrastructure/in/api/`), `MONOT/schema/RetrievalSchemaIntegrationTests.java`, `MONOT/schema/RetrievalMigrationWithoutVectorExtensionTests.java`, `MONOT/support/SharedPostgres.java` y `backend/src/test/resources/contracts/ingest-openapi.json` a `SVCT/` (copiar; el monolito las conserva hasta US4)
- [X] T027 [P] [US2] Copiar a `SVC/shared/` lo necesario de `MONO/shared/`: excepciones, `ResponseCodeEnum` solo con `SLHD_RETR_00001`–`00010`, `SLHD_DBAS_*` y `SLHD_GENE_*`, `ErrorResponse`, `GlobalExceptionHandler`, `RequestIdFilter`, `BlockingCalls`, `ReactorContextConfig` y `GatewayHeaders`, con sus pruebas; sin `ResponseDto`
- [X] T028 [US2] Copiar `MONO/retrieval/{application,domain,infrastructure,config}` a `SVC/` sin `api/` ni `infrastructure/in/api/`, y añadir las dependencias (Spring AI 2.0.2, JDBC, Flyway) en `shoplab-retrieval-service/pom.xml`
- [X] T029 [US2] Copiar `backend/src/main/resources/db/migration/retrieval/V1`–`V4` **sin cambiar su contenido** a `shoplab-retrieval-service/src/main/resources/db/migration/retrieval/`, migrar con el mismo `ModuleSchemaMigrator` del monolito (desviación: no se usa el Flyway de Spring Boot, para conservar historial, códigos de error y pruebas)
- [X] T030 [US2] Escribir en `SVCT/` las pruebas de: consulta de 4000 caracteres de 3 bytes y con `{ } & + # %`; `X-Request-Id` que llega a `ingest` y como `x-shoplab-request-id` al gateway de modelos; `readiness` en rojo sin base o sin extensión `vector`
- [X] T031 [US2] Configurar `shoplab-retrieval-service/src/main/resources/application.yml` según [configuration.md](contracts/configuration.md) (datasource, embeddings por LiteLLM, multipart 11 MB, `server.netty.max-initial-line-length=64KB`, `shoplab.retrieval.*`, sondas y `readiness` con base) y fijar la clave `shoplab.gateway.requestId` desde `X-Request-Id` en `SVC/shared/` hasta que T030 pase
- [X] T032 [US2] Mover `backend/src/test/resources/contracts/retrieval/` y `RetrievalContractBase` a `shoplab-retrieval-service/src/test/`, configurar el plugin de Spring Cloud Contract y `distributionManagement` en `shoplab-retrieval-service/pom.xml` y crear `shoplab-retrieval-service/.github/workflows/publish-stubs.yml`
- [X] T033 [US2] `./mvnw verify` en verde (cobertura ≥ 80 %, ArchUnit, Checkstyle, PMD) y actualizar `shoplab-retrieval-service/README.md`

### Datos y entorno local

- [X] T034 [P] [US2] Crear `infra/postgres/retrieval-db-init.sh` idempotente (rol `retrieval`, base `retrieval`, extensión `vector` como superusuario, `REVOKE CONNECT … FROM PUBLIC` en ambas bases)
- [X] T035 [US2] Crear `scripts/migrate-retrieval.ps1` y `.sh` y `scripts/verify-retrieval-migration.ps1` y `.sh` según [data-migration.md](contracts/data-migration.md): `pg_restore --single-transaction`, fallo si el destino tiene filas sin `--replace`, comparación por recuento, suma de comprobación y vectores por modelo
- [X] T036 [US2] Probar los scripts de T035 contra un PostgreSQL desechable: copia correcta; segunda ejecución sin `--replace` rechazada; una fila alterada hace fallar la verificación; el origen no cambia
- [ ] T037 [US2] Añadir a `infra/docker-compose.yml` los servicios `retrieval-db-init` y `retrieval-service` (puerto de host 8082, imagen fijada) y la variable `LITELLM_KEY_RETRIEVAL` en `litellm-bootstrap`; actualizar `infra/.env.example` e `infra/docker-compose.override.yml.example`
- [ ] T038 [US2] Añadir la clave `retrieval-<entorno>` (`models: [embeddings]`, 5.00 al día) a `infra/litellm/keys.yaml` y actualizar `infra/litellm/tests/test_bootstrap_keys.py`; ejecutar las pruebas de `infra/litellm`
- [X] T039 [P] [US2] Añadir `shoplab-retrieval-service` a `scripts/clone-all.ps1` y al objetivo `clone-all` de `Makefile`, y el trabajo `retrieval-service` a `infra/observability/prometheus/prometheus.yml`

### Release y despliegue en dev, sin tráfico

- [ ] T040 [US2] Rehacer `shoplab-infra/apps/retrieval-service/` (research D-12): `images:` con `ghcr.io/leandroamariles/retrieval-service`, ConfigMap de [configuration.md](contracts/configuration.md), `ExternalSecret` `external-secrets.io/v1` de `aws-secrets-manager`, `securityContext`, `startupProbe`, `/tmp`, 768 Mi, `ServiceMonitor` y política de salida como la del backend
- [ ] T041 [US2] Permitir `retrieval-service` en `shoplab-infra/k8s/base/litellm/networkpolicy.yaml`
- [ ] T042 [US2] Añadir `LITELLM_KEY_RETRIEVAL` y el secreto `retrieval-service` (contraseña de la base, igual a la del compose) a `shoplab-infra/scripts/ensure-secrets.sh`, su prueba y `shoplab-infra/terraform/envs/dev/secrets.auto.tfvars.example`
- [ ] T043 [US2] MANUAL: añadir la contraseña de la base a `secrets.auto.tfvars`, ejecutar `make secrets` y `make up` (los secretos deben existir **antes** de T044, o el Job de claves falla)
- [ ] T044 [US2] Ejecutar `shoplab-infra/scripts/sync-litellm-files.sh` y, APROBACIÓN, fusionar el PR de `shoplab-infra` con T040–T042 (si se quitó el `smoke`, ejecutar antes `make smoke` en local)
- [ ] T045 [US2] APROBACIÓN: publicar la primera release funcional de `shoplab-retrieval-service` y sus stubs, y comprobar `retrieval-service-dev` sincronizada y sana
- [ ] T046 [US2] Provisionar la base, copiar los datos con T035 y comparar monolito y servicio en paralelo (quickstart pasos 3 y 4), con cero embeddings calculados por la migración; anotar en `validation.md`

**Checkpoint**: el servicio responde igual que el monolito y nadie lo usa todavía.

---

## Phase 5: User Story 5 - Indexado seguro con más de una instancia (Priority: P2)

**Goal**: cerrojo en base de datos con arriendo (research D-2). Debe estar publicado antes del corte.

**Independent Test**: dos instancias sobre la misma base indexan cada documento una sola vez.

- [X] T047 [US5] Escribir `SVCT/infrastructure/out/persistence/JdbcIndexingLockAdapterTest.java`: tomar «no hay fila, o `locked_until < now()`»; renovar y liberar solo si «`owner` es el propio»; TTL 90 s con el reloj de la base
- [X] T048 [US5] Escribir `SVCT/IndexingAcrossInstancesIntegrationTest.java`: dos instancias indexan cada documento una vez; muerta la dueña, otra continúa pasado el TTL; búsqueda y subida no se bloquean; renovación perdida detiene la ejecución sin guardar
- [X] T049 [US5] Crear `shoplab-retrieval-service/src/main/resources/db/migration/retrieval/V5__indexing_lock.sql`: `name text` PK (hoy solo `embeddings`), `owner text` no vacío, `locked_until timestamptz` no nulo
- [X] T050 [US5] Crear `SVC/application/port/out/IndexingLockPort.java` y `SVC/infrastructure/out/persistence/JdbcIndexingLockAdapter.java`; usarlo en `SVC/application/usecase/DocumentEmbedder.java` (renovar antes de cada lote, liberar al terminar y en el apagado) y borrar `IndexingGuard.java`
- [X] T051 [US5] Subir los reintentos a 12 cada 10 s en `SVC/infrastructure/in/event/IndexAfterIngestListener.java` y aplicarlos también en `IndexOnStartupRunner.java` cuando la ejecución se omita, con sus pruebas, hasta que T047 y T048 pasen
- [ ] T052 [US5] APROBACIÓN: publicar la release y comprobar que dev se actualiza **sin release del monolito** (SC-004); anotar en `validation.md`

**Checkpoint**: el servicio está listo para recibir tráfico.

---

## Phase 6: User Story 4, parte 1 - El chat puede llamar al servicio (release A)

**Goal**: el monolito sabe usar el servicio, sin cambiar su comportamiento (`mode` por defecto `in-process`).

**Independent Test**: con `mode=http` en local, el chat responde con las mismas citas; sin la variable, nada cambia.

- [ ] T053 [US4] Publicar el cliente generado: aplicar T004 en `shoplab-contracts/generators/java/pom.xml`, actualizar `shoplab-contracts/README.md` y, APROBACIÓN, publicar `shoplab-contracts` 1.3.0
- [ ] T054 [P] [US4] Escribir `MONOT/chat/infrastructure/out/knowledge/HttpKnowledgeSearchClientTests.java` (WireMock) con la tabla de [chat-client.md](contracts/chat-client.md): orden, sin texto, título vacío, vacío, `4xx`/`5xx`/conexión/tiempo/cuerpo ilegible → `SLHD_CHAT_00004`, sin reintentos, `X-Request-Id`, `traceparent`, codificación de la consulta
- [ ] T055 [P] [US4] Cambiar `MONOT/chat/infrastructure/out/knowledge/ChatRetrievalContractConsumerTest.java` a los stubs `com.shoplab:shoplab-retrieval-service` con versión fijada, y dar a `.github/workflows/ci.yml` el acceso decidido en T003
- [ ] T056 [US4] Añadir `shoplab-contracts` 1.3.0 como dependencia en `backend/pom.xml`; crear `MONO/chat/config/RetrievalClientConfig.java` y `RetrievalClientProperties.java` (`base-url`, `timeout` 15 s); reescribir `MONO/chat/infrastructure/out/knowledge/HttpKnowledgeSearchClient.java` sobre la interfaz generada
- [ ] T057 [US4] Propagar `traceparent` entrante junto a `X-Request-Id` (`MONO/shared/infrastructure/in/rest/filter/RequestIdFilter.java`) y declarar las propiedades en `backend/src/main/resources/application.yml` hasta que T054 y T055 pasen
- [ ] T058 [US4] APROBACIÓN: fusionar y publicar la release A del monolito; comprobar en dev que el chat sigue igual y subir `BACKEND_IMAGE` en `infra/docker-compose.yml`
- [ ] T059 [US4] En local, con `mode=http` hacia `retrieval-service`, comprobar citas, servicio caído (`SLHD_CHAT_00004`), cortesías y la consulta larga (quickstart paso 6)

**Checkpoint**: el corte es solo configuración.

---

## Phase 7: User Story 3 - El gateway entrega documentos y búsqueda al servicio (Priority: P1)

**Goal**: corte en dev y local, reversible. Incluye el paso del chat a HTTP (US4) en el mismo commit.

**Independent Test**: quickstart pasos 5 y 7: métricas idénticas, pantalla de documentos igual, reversión ensayada.

- [ ] T060 [US3] Tomar la línea base con `uv run python -m shoplab_eval retrieval` en `shoplab-eval/` contra el sistema actual y guardar resumen y detalle en `specs/LAB-129-retrieval-service-extraction/baseline/`; medir también el p95 de la búsqueda del chat
- [ ] T061 [US3] Preparar el PR de `shoplab-infra` con un solo commit según [configuration.md](contracts/configuration.md): variables del gateway en `apps/gateway/overlays/dev/kustomization.yaml` y del monolito en `k8s/overlays/dev/kustomization.yaml`
- [ ] T062 [US3] APROBACIÓN: avisar y suspender las subidas, hacer copia de la base `shoplab` fuera del repositorio, migrar con `--replace`, verificar sin diferencias y comprobar el servicio sano
- [ ] T063 [US3] MANUAL: fusionar el commit del corte
- [ ] T064 [US3] Verificar: `routeId=documents` y `knowledge` hacia `retrieval-service` en el registro del gateway; `/documentos` lista y sube; el chat cita el documento nuevo; evaluación de LAB-28 **idéntica** a la línea base (SC-001); p95 con menos de 100 ms añadidos (SC-005). Reanudar las subidas
- [ ] T065 [US3] APROBACIÓN: ensayar la reversión (revertir el commit, medir el tiempo, < 10 min, SC-007) y volver a aplicar; anotar que los documentos subidos al servicio se recargan a mano
- [ ] T066 [US3] Hacer del servicio el destino predeterminado en `infra/docker-compose.yml` (rutas `documents` y `knowledge` del gateway, `mode=http`, URL y `auto-index=false` del backend) y ajustar `scripts/test-e2e.ps1` y `.sh`

**Checkpoint**: el servicio atiende todo el tráfico. Si las métricas difieren, no pasar a la fase 8.

---

## Phase 8: User Story 4, parte 2 - El módulo desaparece del monolito (release B)

**Goal**: sin código, migraciones, rutas ni configuración de `retrieval` en el monolito.

**Independent Test**: `./mvnw -B clean verify` en verde y ninguna referencia a `helpdesk.retrieval`.

- [ ] T067 [US4] Sustituir el módulo real por un doble de `KnowledgeContextPort` en `MONOT/chat/{ChatCitations,ChatMemory,RagAnswering,RagConfiguration,TraceResilience,TraceSpecialTurns,TurnTrace}IntegrationTests.java`, `MONOT/chat/support/` y `MONOT/blockhound/TracingBlockHoundTests.java`
- [ ] T068 [US4] Borrar `MONO/retrieval/`, `backend/src/main/resources/db/migration/retrieval/`, `MONOT/retrieval/`, las dos pruebas de esquema de `retrieval`, `backend/src/test/resources/contracts/retrieval/`, `ingest-openapi.json` y la correspondencia `retrieval` en `backend/pom.xml`
- [ ] T069 [US4] Borrar `KnowledgeSearchAdapter`, `ContextFragmentMapper`, su prueba y la propiedad `shoplab.retrieval.client.mode`; quitar de `backend/src/main/resources/application.yml` lo que era del módulo (`ingest`, `upload`, `embedding`, multipart)
- [ ] T070 [US4] Quitar los `SLHD_RETR_*` de `MONO/shared/exception/ResponseCodeEnum.java` y lo que quede sin uso (manejadores, `spring-ai-rag`); ajustar `ModuleArchitectureTests`, `SchemaIsolationTests`, `ModuleSchemasIntegrationTests` y `ResponseCodeEnumTests`
- [ ] T071 [US4] `./mvnw -B clean verify` en verde con cobertura ≥ 80 % y regenerar `docs/modulos/`
- [ ] T072 [US4] Quitar `embeddings` de la clave del backend en `infra/litellm/keys.yaml` (y su prueba) y las variables sobrantes del backend en `infra/docker-compose.yml`
- [ ] T073 [US4] APROBACIÓN: publicar la release B y, en `shoplab-infra`, quitar `SHOPLAB_RETRIEVAL_INGEST_BASE_URL` y las variables del corte que ya no aplican (`k8s/base/backend/kustomization.yaml`, `k8s/overlays/dev/kustomization.yaml`, conservando la URL del servicio), y sincronizar las claves con `scripts/sync-litellm-files.sh`
- [ ] T074 [US4] Verificar en dev quickstart paso 10 y que la evaluación de LAB-28 sigue idéntica

**Checkpoint**: una sola copia de la base de conocimiento en uso.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T075 [P] Escribir `docs/adr/0033-extraccion-de-retrieval-service.md` (base propia y sus límites, cerrojo con arriendo, corte, reversión, `smoke`), marcar ADR-0017 como parcialmente sustituido y actualizar `docs/adr/README.md`
- [ ] T076 [P] Actualizar `docs/mapa-servicios.md` (sin MCP ni eventos todavía), `docs/modulos.md`, `docs/contratos.md`, `docs/base-conocimiento.md`, `docs/gateway-de-modelos.md`, `docs/observabilidad.md` y `README.md`
- [ ] T077 [P] Actualizar `docs/fases/F5-mover-rutas-al-gateway.md` y `docs/fases/F5-entorno-local-multi-repo.md`, incluido el borrado manual del esquema antiguo (`DROP SCHEMA retrieval CASCADE` en `shoplab`, solo con copia y tras verificar; **no se ejecuta en esta historia**)
- [ ] T078 Generar `specs/LAB-129-retrieval-service-extraction/requests.http` contra el servicio según `.ai/rules` (un caso por requisito y escenario alcanzable por HTTP)
- [ ] T079 Completar `quickstart.md` y cerrar `validation.md` con el resultado de SC-001 a SC-008
- [ ] T080 APROBACIÓN: actualizar en Jira LAB-129 el criterio del `smoke` según T022 y las desviaciones registradas

---

## Dependencies & Execution Order

```text
Setup → Foundational → US1 → US2 → US5 → US4 (release A) → US3 (corte) → US4 (release B) → Polish
```

- T011 (mover `v1`) antes de T012–T017. T015 antes de T017.
- T022–T023 antes de cualquier PR de `shoplab-infra` que toque `k8s/` (T044, T061).
- T043 antes de T044 (secretos antes de sincronizar las claves).
- T045 (stubs publicados) antes de T055.
- T062–T063 en la misma ventana, sin subidas entre medias.
- T068 solo después de T064 en verde.

### Parallel Opportunities

- US1: T007, T008, T009, T010 y T024; el bloque del `smoke` (T019–T023) va en paralelo con la plantilla.
- US2: T027 con T034; T039 con cualquiera.
- US4 parte 1: T054 y T055.
- Polish: T075, T076 y T077.

```bash
# US1, tras T005:
T007 archivos de build en TPL/
T008 workflows en TPL/.github/
T009 shoplab-ci/publish-image.yml
T024 infra/docker-compose.yml
```

## Implementation Strategy

- **MVP (US1)**: plataforma arreglada. Vale por sí sola: desbloquea esta extracción, LAB-59 y LAB-130.
- **Entrega 2 (US2 + US5)**: servicio desplegado sin tráfico; no hay riesgo para los usuarios.
- **Entrega 3 (release A + US3)**: corte reversible.
- **Entrega 4 (release B + Polish)**: retirada; a partir de aquí volver atrás exige revertir código.

## Notes

- Un commit por tarea o grupo lógico: `LAB-129 type(scope): …`; título de PR `type(scope): [LAB-129] …`.
- No borrar volúmenes ni el esquema `retrieval` antiguo.
- Detenerse en cada checkpoint.
