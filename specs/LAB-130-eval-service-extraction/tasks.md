---
description: "Task list for LAB-130 eval-service extraction"
---

# Tasks: LAB-130 Extraer eval-service del monolito

**Input**: Design documents from `/specs/LAB-130-eval-service-extraction/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: obligatorias (constitución IV). Cada prueba nueva se escribe antes del código y se ve fallar; las pruebas
existentes de `eval` se mueven con su código y deben pasar en el repo nuevo.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ir en paralelo (archivos distintos, sin dependencias pendientes)
- **[USn]**: historia de la spec
- **MANUAL**: lo hace la persona dueña de las cuentas. Los valores de secretos **no** se escriben en el chat, en
  archivos versionados ni en commits.
- **APROBACIÓN**: publica algo fuera de la máquina o afecta a datos (repositorio, paquete, tag, merge, release,
  despliegue, migración); se confirma antes.
- **VERIFICAR**: comprueba un supuesto marcado «por verificar» en research.md y anota el resultado allí. Si el supuesto
  falla, detenerse y revisar la decisión citada antes de seguir.

**Rutas**: las que empiezan por `shoplab-…/` son de repositorios hermanos; el resto son de `shoplab-helpdesk`.
`SVC` = `shoplab-eval-service/src/main/java/com/shoplab/eval`,
`SVCT` = `shoplab-eval-service/src/test/java/com/shoplab/eval`,
`MONO` = `backend/src/main/java/com/shoplab/helpdesk`,
`MONOT` = `backend/src/test/java/com/shoplab/helpdesk`.

**El orden de las fases sigue las dependencias** (plan: fases A–D): US1 y US2 forman la fase A; US3 abarca las fases
B y C (datos, despliegue y corte); US5 (ADR y docs) va **antes** del retiro; US4 es la fase D y va al final, en un PR
aparte, para que el monolito siga siendo la reversión durante la verificación.

---

## Phase 1: Setup

- [X] T001 Crear la rama `feature/LAB-130-eval-service-extraction` desde `origin/main` en `shoplab-infra/` y `shoplab-contracts/` (comprobar antes que cada árbol local esté limpio y en una rama conocida)
- [X] T002 [P] Crear `specs/LAB-130-eval-service-extraction/validation.md` con una sección por paso de `quickstart.md` para guardar la evidencia
- [X] T003 [P] VERIFICAR en `shoplab-infra/` el estado de `apps/eval-service/` (¿existe el esqueleto de LAB-104? ¿versión, variables?), `apps/gateway/overlays/dev/kustomization.yaml` (nombre de la variable de la ruta `eval`) y `apps/backend/`; anotar en research D-7 y D-8
- [X] T004 [P] VERIFICAR en `shoplab-eval/` cómo se configura la URL de guardado y el token de la herramienta Python; anotar en research D-6
- [X] T005 [P] VERIFICAR en `shoplab-gateway/` que la ruta `/api/eval/**` se define por variable de entorno y que reenvía `X-Eval-Token` y `traceparent`; anotar en research D-7
- [X] T006 [P] VERIFICAR la convención de paquete y estructura en `shoplab-chat-service/` y `shoplab-retrieval-service/` (¿`com.shoplab.<servicio>`? ¿copia mínima de `shared`?) y que `shoplab-service-template` es un repositorio plantilla; anotar en plan «Structure Decision»

---

## Phase 2: Foundational (riesgos que condicionan el diseño)

**⚠️ CRITICAL**: si T007 o T008 fallan, detenerse y revisar research D-4 o D-5 antes de seguir.

- [X] T007 VERIFICAR que un repositorio nuevo resuelve desde GitHub Packages `com.shoplab:shoplab-contracts` y los stubs `com.shoplab:shoplab-chat-service:<versión>:stubs` con `GITHUB_TOKEN` (workflow temporal en una rama de `shoplab-eval-service` una vez creado en T009, o en una rama de `shoplab-chat-service/`); anotar el resultado en research D-4 y borrar la rama. Si no puede, decidir entre dar acceso al paquete (MANUAL) o un token `read:packages` como secreto (MANUAL)
- [X] T008 Probar en `shoplab-contracts/generators/java/pom.xml` la generación de la interfaz de `chat` (`GET /api/assistant/settings`) y que compila y deserializa una respuesta bajo Spring Boot 4.1.2 con un proyecto de prueba en el scratchpad (riesgo Jackson 2/3 de LAB-129); anotar en research D-4

**Checkpoint**: el cliente generado y el acceso a paquetes son viables.

---

## Phase 3: User Story 1 - El historial de evaluaciones vive en su propio servicio con sus datos (Priority: P1) 🎯 MVP

**Goal**: `shoplab-eval-service` construye, prueba y publica la misma API que el monolito, sin depender de él (FR-001…FR-012).

**Independent Test**: levantar el servicio con una copia migrada de los datos junto al monolito intacto; las mismas
peticiones dan respuestas idénticas (quickstart 1).

### Repositorio y código

- [X] T009 [US1] APROBACIÓN Crear `shoplab-eval-service` desde `shoplab-service-template` con `gh` (público, fusión por squash, variable `DEPLOY_APP_ID`); sin editar nada más que el nombre (FR-001)
- [X] T010 [US1] MANUAL Cargar `DEPLOY_APP_PRIVATE_KEY` y `RELEASE_PLEASE_TOKEN` en el repositorio nuevo (sin escribir los valores en el chat)
- [X] T011 [US1] Copiar `MONO/eval/**` a `SVC/` (api, application, config, domain, infrastructure) cambiando el paquete a `com.shoplab.eval` y manteniendo los nombres de clases
- [X] T012 [P] [US1] Copiar a `SVC/shared/` solo lo que `eval` usa: `ResponseCodeEnum` (subconjunto `SLHD_EVAL_00001`…`00006` y `SLHD_GENE_00001`/`00002`, sin renombrar), `GlobalExceptionHandler`, `ErrorResponse`, `RequestIdFilter`, `GatewayHeaders`, excepciones base, `BlockingCalls`, `ModuleSchema`, `ModuleSchemaMigrator`, `DatabaseConfig` y `ReactorContextConfig` (FR-011)
- [X] T013 [P] [US1] Copiar `backend/src/main/resources/db/migration/eval/V1__baseline.sql` y `V2__evaluation_history.sql` a `shoplab-eval-service/src/main/resources/db/migration/eval/` sin tocar un byte (FR-004) y comprobar con `git hash-object` que coinciden
- [X] T014 [US1] Escribir `application.yml` del servicio con las propiedades de `contracts/configuration.md` (sin `spring.ai`, sin OpenTelemetry, sin `mode`)
- [X] T015 [P] [US1] Copiar las pruebas de `MONOT/eval/**` y `backend/src/test/resources/eval/queries/**` a `SVCT/` y su `src/test/resources/`, ajustando paquetes; incluir `EvalHistoryEquivalenceTests`, `EvalHistoryNoTextTests`, `EvalNoTextStoredTests`, `EvalApiIntegrationTests` y `RunComparatorTests`
- [X] T016 [US1] Ejecutar `mvn verify` en `shoplab-eval-service` hasta que pasen las pruebas movidas, Checkstyle, PMD y JaCoCo ≥ 80 %; corregir solo lo necesario por el cambio de paquete
- [X] T017 [P] [US1] Prueba ArchUnit en `SVCT/` que falle si `eval-service` importa algo de `com.shoplab.helpdesk` o de `chat` (FR-002, SC-005); verla fallar y luego pasar
- [X] T018 [P] [US1] Prueba de que `X-Eval-Token` vacío o ausente devuelve `403 SLHD_EVAL_00001` en `POST /api/eval/runs`, `GET /api/eval/runs/{id}` y `GET /api/eval/settings`, y que `/api/eval/history/**` responde sin token (FR-009)
- [X] T019 [P] [US1] Prueba de `POST` repetido con el mismo `runId` → `201` y luego `409 SLHD_EVAL_00003`, sin alterar la primera (spec US1-4)
- [X] T020 [P] [US1] Prueba de que las propiedades desconocidas y un cuerpo ilegible dan `422 SLHD_EVAL_00002` / `400 SLHD_GENE_00002` como el monolito

### Contratos y publicación

- [X] T021 [P] [US1] Escribir `shoplab-contracts/openapi/eval-service.yaml` a partir de `contracts/http-api.md` (6 rutas, esquemas de `EvalRunRequest`, `EvalRunSavedResponse`, `StoredRunResponse`, `EvalSettingsResponse`, `RunListResponse`, `RunDetailResponse`, `RunComparisonResponse`, `ErrorResponse`) y la validación del contrato en su CI (FR-010)
- [X] T022 [US1] Contratos Spring Cloud Contract en `shoplab-eval-service/src/test/resources/contracts/eval/` (guardar, leer, no encontrado, ajustes, listado) y la verificación como productor con `WEBTESTCLIENT`; verlos fallar antes de implementar el ajuste necesario (FR-010)
- [X] T023 [US1] Copiar `publish-stubs.yml` del monolito al repo nuevo apuntando a `src/test/resources/contracts/**` y publicar `com.shoplab:shoplab-eval-service:<versión>:stubs`
- [X] T024 [US1] Pipelines `ci.yml`, `release.yml` y `release-please.yml` del repo nuevo: confirmar que corren sin editar la plataforma y que publican `ghcr.io/leandroamariles/eval-service:vX.Y.Z` firmada y verificada (SC-008 comprobado con este repo)
- [X] T025 [US1] APROBACIÓN Fusionar el PR inicial, dejar que release-please publique la primera release y comprobar imagen, firma y stubs
- [X] T026 [US1] MANUAL Hacer público el paquete `eval-service` en GHCR (no reversible)

**Checkpoint**: `eval-service` publicado con su contrato; US1 verificable en paralelo al monolito cuando exista la base (T033).

---

## Phase 4: User Story 2 - eval-service lee la configuración del asistente desde chat-service (Priority: P1)

**Goal**: `GET /api/eval/settings` obtiene la configuración de `chat-service` por HTTP con el contrato publicado, y esa
dependencia está probada (FR-013…FR-016).

**Independent Test**: con los stubs de `chat-service`, `GET /api/eval/settings` devuelve la configuración del stub; con
el stub parado, `503 SLHD_EVAL_00005`; el guardado y el historial siguen funcionando (quickstart 2).

- [X] T027 [US2] Escribir primero `SVCT/infrastructure/out/chat/EvalChatContractConsumerTest` (movido del monolito) para que consuma `com.shoplab:shoplab-chat-service:<versión>:stubs`; verlo fallar con el cliente aún sin migrar (FR-015)
- [X] T028 [US2] Configurar en `shoplab-eval-service/pom.xml` la generación del cliente reactivo de `chat` desde `shoplab-contracts` y la dependencia de los stubs de `chat-service` con versión fija
- [X] T029 [US2] Reescribir `SVC/infrastructure/out/chat/HttpAssistantSettingsClient` sobre el cliente generado: eliminar el modo `in-process`, `ChatAssistantSettingsAdapter` y toda referencia a código de `chat`; conservar `AssistantSettingsPort` (FR-013)
- [X] T030 [P] [US2] Pruebas de `HttpAssistantSettingsClientTests` con WireMock: éxito, timeout (`5s` configurable), 5xx, cuerpo inesperado → `EvalStoreUnavailableException` → `503 SLHD_EVAL_00005` (FR-014)
- [X] T031 [P] [US2] Prueba de propagación de `X-Request-Id` y `traceparent` hacia `chat-service` (FR-014)
- [X] T032 [P] [US2] Prueba de aislamiento: con `chat-service` caído, `POST /api/eval/runs` y `GET /api/eval/history/runs` responden con normalidad (FR-016, SC-006)

**Checkpoint**: US2 completa; `mvn verify` pasa con la prueba de contrato eval → chat en verde.

---

## Phase 5: User Story 3 - Datos, despliegue y corte de rutas (Priority: P1)

**Goal**: base propia con los datos migrados, servicio desplegado en dev, ruta del gateway movida y herramienta y
`admin-eval` funcionando sin cambios (FR-003…FR-006, FR-017…FR-023).

**Independent Test**: en dev, cambiar el destino de `/api/eval/**`; ejecutar la herramienta con solo la URL nueva; abrir
`admin-eval`; revertir y comprobar que el monolito atiende de nuevo (quickstart 3).

### Base de datos y scripts (local)

- [X] T033 [P] [US3] Crear `infra/postgres/eval-db-init.sh` idempotente, copia de `chat-db-init.sh`: rol `eval`, base `eval` sin `pgvector`, `REVOKE CONNECT` a `PUBLIC` en `eval` y en la base del monolito (FR-003)
- [X] T034 [US3] Añadir a `infra/docker-compose.yml` el servicio de un solo disparo `eval-db-init`, y `eval-service` (imagen publicada en T025, puerto de depuración `8084`, variables de `contracts/configuration.md`, red hacia `chat-service`); añadir `EVAL_DB_PASSWORD` y `SHOPLAB_EVAL_PERSISTENCE_TOKEN` a `infra/.env.example` (FR-023)
- [X] T035 [P] [US3] Crear `infra/postgres/eval-migrate.sh` (`pg_dump --schema=eval` + `pg_restore` bajo `SET ROLE eval`, una transacción, `--replace`) y `scripts/migrate-eval.{sh,ps1}` modelados en `migrate-chat.*` (FR-005)
- [X] T036 [P] [US3] Crear `infra/postgres/eval-verify.sh` y `scripts/verify-eval-migration.{sh,ps1}` con los criterios de `contracts/data-migration.md`: recuentos, MD5 por tabla (incluidas columnas `jsonb`), `last_value >= max(id)` de las tres secuencias, origen intacto (FR-004…FR-006)
- [X] T037 [US3] Probar los scripts contra un PostgreSQL local con datos de `eval` reales o de la fixture `EvalRunFixtures`; incluir un caso con una secuencia atrasada que `verify` debe rechazar; guardar la salida en `validation.md`
- [X] T038 [P] [US3] Añadir `shoplab-eval-service` a `scripts/clone-all.ps1`

### Línea base y paridad

- [X] T039 [US3] Congelar en `specs/LAB-130-eval-service-extraction/baseline/monolith/` la salida del monolito: recuentos y MD5 por tabla, y las respuestas de `GET /api/eval/history/runs`, `/runs/{id}` y `/compare` para un conjunto fijo de ids (FR-026, research D-10)
- [X] T040 [US3] Script `scripts/compare-eval-baseline.{sh,ps1}` que repite las peticiones contra el servicio y compara con la línea base, ignorando solo `traceUrl` si cambia la plantilla; probarlo contra el propio monolito (debe dar 0 diferencias)

### Despliegue en dev

- [X] T041 [US3] En `shoplab-infra/apps/eval-service/` completar el esqueleto: `POSTGRES_*` desde el `ExternalSecret` `eval-service-db-secret`, `SHOPLAB_EVAL_PERSISTENCE_TOKEN`, `SHOPLAB_CHAT_SETTINGS_CLIENT_BASE_URL=http://chat-service:8080`, `SHOPLAB_EVAL_HISTORY_TRACE_URL_TEMPLATE`, `newTag` de la release de T025; y `eval-service` en los secretos de dev que haga falta (FR-020)
- [X] T042 [US3] MANUAL Cargar `EVAL_DB_PASSWORD` y `SHOPLAB_EVAL_PERSISTENCE_TOKEN` en los secretos de dev (`secrets.auto.tfvars` / Secrets Manager) y aprovisionar rol y base `eval` en el PostgreSQL de dev con el equivalente de `eval-db-init`
- [X] T043 [US3] APROBACIÓN Fusionar en `shoplab-infra` el alta de `eval-service` y comprobar en Argo CD que queda sincronizado y sano (US3-1, FR-020)

### Migración y corte

- [X] T044 [US3] APROBACIÓN Ventana de corte: vaciar `SHOPLAB_EVAL_PERSISTENCE_TOKEN` en el monolito de dev (guardado → 403, lectura intacta), ejecutar `migrate-eval` y `verify-eval-migration`, y anotar el resultado en `validation.md` (FR-021)
- [X] T045 [US3] Ejecutar `compare-eval-baseline` contra el servicio con los datos migrados: 0 diferencias (SC-001)
- [X] T046 [US3] APROBACIÓN Commit de corte en `shoplab-infra/apps/gateway/overlays/dev/kustomization.yaml` con `SHOPLAB_GATEWAY_ROUTES_EVAL_URI=http://eval-service:8080`; fusionar y esperar la sincronización (FR-019)
- [X] T047 [US3] En `shoplab-eval/` cambiar solo la URL base de guardado (y el token si el valor cambia), sin tocar código; ejecutar una corrida real y comprobar que se guarda en el servicio (FR-017, SC-002)
- [X] T048 [US3] Abrir `admin-eval` en dev: listar, abrir y comparar una migrada con la nueva; confirmar 0 cambios en `shoplab-frontend` (FR-018, SC-003, US3-4/5)
- [X] T049 [US3] Fijar el token en `eval-service`, reanudar el guardado y verificar que una ejecución nueva recibe un `id` mayor que todos los migrados (FR-006)
- [X] T050 [US3] Probar la reversión: devolver la ruta al monolito con el commit inverso, medir el tiempo (< 10 min, SC-007), comprobar que el monolito atiende, y volver a mover la ruta; anotar en `validation.md` (FR-022)
- [X] T051 [P] [US3] Comprobar con `kubectl`/Argo que `eval-service` y `chat-service` conviven y que `GET /api/eval/settings` por el gateway devuelve la configuración real

**Checkpoint**: US3 funcional. El monolito sigue desplegado pero sin tráfico de `eval`.

---

## Phase 6: User Story 5 - Documentación y preparación para LAB-69 (Priority: P3)

**Goal**: ADR, mapa y documentos al día **antes** del retiro, para que quien revise la fase D tenga el contexto
(FR-027).

**Independent Test**: los documentos describen `eval-service` y no mencionan el monolito como destino de `eval`.

- [X] T052 [P] [US5] Crear `docs/adr/0035-extraccion-de-eval-service.md` (base propia sin `pgvector`, migración por volcado y secuencias, corte por configuración, retiro de `backend/`, reversión, conservación del esquema antiguo, superusuario compartido) y enlazarlo en `docs/adr/README.md`
- [X] T053 [P] [US5] Actualizar `docs/mapa-servicios.md`: sección 3.3 de `eval-service` con las rutas reales (`/api/eval/runs`, `/api/eval/settings`, `/api/eval/history/**`), las tablas reales (`eval_run`, `eval_case`, `eval_result`), y retirar `POST /api/eval/cases/promote` y los eventos Kafka que no existen (marcándolos como previstos)
- [X] T054 [P] [US5] Actualizar `docs/modulos.md`, `docs/contratos.md` y `docs/modulos/admin-eval.md` (el servicio, el OpenAPI y el cliente de `chat`)
- [X] T055 [P] [US5] Añadir a `docs/fases/` o al documento de fase de F5 el procedimiento de corte y de reversión, y el paso manual posterior `DROP SCHEMA eval CASCADE` con copia previa (FR-025)
- [X] T056 [US5] Anotar en el ADR o en el mapa que la evaluación online de LAB-69 se añade en `eval-service` sin crear otro servicio (US5-2)

---

## Phase 7: User Story 4 - El monolito se retira (Priority: P2)

**Goal**: `backend/` desaparece del repositorio y de los despliegues, sin tocar los datos de Olist (FR-024, FR-025).

**Independent Test**: CI y compose en verde sin `backend/`; 0 referencias; los esquemas de Olist y el antiguo `eval`
siguen en PostgreSQL (quickstart 4).

**Puerta de entrada**: T045…T050 verificados y al menos una ejecución real guardada y leída por el servicio. Va en un
PR aparte.

- [ ] T057 [US4] Crear con `git tag` el punto de restauración `backend-final-pre-LAB-130` y una copia (`pg_dump`) de la base `eval` y del esquema `eval` de `shoplab`; documentar la restauración en el ADR-0035 (US4-4)
- [ ] T058 [US4] Inventariar con `git grep -n -i "backend"` las referencias fuera de `backend/` (workflows, compose, Prometheus, README, `.gitignore`, Qodana/Trivy, `release-please`, docs) y guardar la lista en `validation.md`
- [ ] T059 [US4] Eliminar `backend/` completo (código, migraciones, pruebas, `Dockerfile`, `qodana.*`, `.mvn`, `mvnw`)
- [ ] T060 [P] [US4] Retirar de `.github/workflows/` los filtros y jobs de `backend` en `ci.yml`, la publicación de la imagen en `images.yml`, `publish-stubs.yml` y las entradas de `release-please` que solo servían al monolito; conservar lo que protege al resto (Python, notebooks, ml, infra)
- [ ] T061 [P] [US4] Retirar el servicio `backend` de `infra/docker-compose.yml`, su scraping en `infra/prometheus/` y `LITELLM_KEY_BACKEND` de compose, `.env.example` y del gateway de modelos si ya no tiene consumidores; ajustar el scraping para que apunte a `eval-service`
- [ ] T062 [P] [US4] En `shoplab-infra/`: retirar `apps/backend` de la ApplicationSet y el despliegue, y la ruta por defecto del gateway hacia `backend` (FR-024); además, `scripts/quality.sh` lee el token del secreto `backend-eval`: pasarlo a `eval-service-eval` antes de borrar el del monolito
- [ ] T063 [US4] Comprobar que `docker compose -f infra/docker-compose.yml up` arranca desde cero sin `backend/` y con `eval-service` (FR-023, US4-2)
- [ ] T064 [US4] Verificar con `psql` que los esquemas `catalog`, `orders`, `tickets` (Olist) y `eval` siguen intactos y que no hay `DROP SCHEMA` en ningún archivo del PR (FR-025, SC-008)
- [ ] T065 [US4] Enmendar `.specify/memory/constitution.md` (PATCH 1.0.3): la restricción de stack deja de nombrar `backend/` y pasa a servicios por repositorio; actualizar el informe de impacto; revisar `.ai`, `GEMINI.md` y `README.md` si citan `backend/` (research D-9)
- [ ] T066 [US4] Retirar la imagen `backend` de GHCR o dejarla deprecada con una nota (APROBACIÓN)
- [ ] T067 [US4] APROBACIÓN Fusionar el PR de retiro, comprobar que CI y dev siguen en verde y que `git grep -n "backend/"` no devuelve referencias vivas (SC-008)

---

## Phase 8: Polish & Cross-Cutting

- [X] T068 [P] Generar `specs/LAB-130-eval-service-extraction/requests.http` (regla `http-test-requests`): un caso por escenario de aceptación y por regla de la spec (token, 409 repetido, historial, comparación, 422 no comparable, settings con chat caído)
- [X] T069 [P] Ejecutar la skill `new-properties` y entregar el YAML de propiedades nuevas para el yml de cada entorno
- [ ] T070 Pasar `quickstart.md` completo y guardar la evidencia en `validation.md`
- [X] T071 [P] Comprobar cobertura ≥ 80 % y 0 violaciones de Checkstyle/PMD en `shoplab-eval-service`
- [ ] T072 Marcar este `tasks.md` y abrir los PR con título `type(scope): [LAB-130] descripción` (skill `commit-and-push`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (1)** → **Foundational (2)** → **US1 (3)** → **US2 (4)** → **US3 (5)** → **US5 (6)** → **US4 (7)** → **Polish (8)**.
- US1 y US2 forman la fase A del plan; T033…T038 de US3 pueden empezar en cuanto exista el repo (T009) y en paralelo a US2.
- US5 (docs) puede escribirse en paralelo a US3, pero **debe estar fusionada antes de T057**.
- US4 no puede empezar hasta que T045…T050 estén verificados.

### Dependencias por tarea

- T011 depende de T009 y T006; T015 de T011; T016 de T011…T015; T022 de T021; T024 de T016; T025 de T024.
- T027…T029 dependen de T007 y T008; T029 de T028.
- T034 depende de T025 y T033; T036 de T035; T037 de T035 y T036; T040 de T039.
- T041 depende de T003 y T025; T043 de T041 y T042; T044 de T043 y T037; T046 de T045; T047 de T046; T048 de T047.
- T057…T067 dependen de T050; T059 de T058; T063 de T059 y T061; T065 de T059.

### Parallel Opportunities

- T003–T006 entre sí; T012, T013, T015, T017–T021 una vez hecho T011.
- T030–T032 entre sí; T033, T035, T036, T038 entre sí; T052–T055 entre sí; T060–T062 entre sí.

## Implementation Strategy

### MVP

US1 + US2 + US3 hasta T050: el historial vive en su servicio, con datos y rutas movidas, y el monolito sigue como
reversión. Es el punto de parada seguro.

### Entrega incremental

1. Fase A (US1, US2): servicio publicado y contrato eval → chat verde, sin tráfico real.
2. Fase B/C (US3): datos, dev y corte. **Parar y validar** (T050).
3. US5: documentación al día.
4. Fase D (US4): retiro en un PR aparte, con restauración documentada.

## Notes

- Las tareas MANUAL y APROBACIÓN son puntos de parada: no se ejecutan sin confirmación.
- No escribir valores de secretos en archivos versionados ni en el chat.
- No ejecutar `DROP SCHEMA` en ninguna tarea; el borrado del esquema `eval` antiguo es un paso manual posterior.
- Commits atómicos por tarea o grupo lógico; `type(scope): description` en inglés y `[LAB-130]` en el título del PR.
