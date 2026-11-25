---
description: "Task list for LAB-107 strangler gateway"
---

# Tasks: LAB-107 Gateway estrangulador antes de extraer servicios

**Input**: Design documents from `/specs/LAB-107-strangler-gateway/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/routing.md](contracts/routing.md), [contracts/configuration.md](contracts/configuration.md),
[quickstart.md](quickstart.md)

**Tests**: obligatorias (constitución IV). Cada prueba se escribe antes del código y se ve fallar.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: puede ir en paralelo (archivos distintos, sin dependencias pendientes)
- **[USn]**: historia de la spec
- **MANUAL**: lo hace la persona dueña de las cuentas. Los valores de secretos **no** se escriben en el chat, en
  archivos ni en commits.
- **APROBACIÓN**: publica algo fuera de la máquina (repositorio, merge, release, despliegue); se confirma antes.

**Rutas**: `shoplab-gateway/` y `shoplab-infra/` son repositorios hermanos; las rutas sin prefijo son de
`shoplab-helpdesk`. `MAIN` = `shoplab-gateway/src/main/java/com/shoplab/gateway`, `TEST` =
`shoplab-gateway/src/test/java/com/shoplab/gateway`.

---

## Phase 1: Setup (repositorio, secretos y esqueleto)

**Purpose**: dejar `shoplab-gateway` creado, con sus credenciales y compilando.

- [X] T001 MANUAL / APROBACIÓN: crear el repositorio `leandroAmariles/shoplab-gateway` con `gh repo create leandroAmariles/shoplab-gateway --add-readme --description "ShopLab API gateway (LAB-107)"` y la misma visibilidad que los demás repos ShopLab (comprobarla con `gh repo view leandroAmariles/shoplab-ci --json visibility`)
- [X] T002 MANUAL: crear un token de acceso limitado a `shoplab-gateway` (Contents y Pull requests: lectura y escritura) y cargarlo con `gh secret set RELEASE_PLEASE_TOKEN -R leandroAmariles/shoplab-gateway` (el valor se pega en el prompt)
- [X] T003 MANUAL: cargar las credenciales de la GitHub App de despliegue como secreto y variable de **repositorio**: `gh variable set DEPLOY_APP_ID -R leandroAmariles/shoplab-gateway` y `gh secret set DEPLOY_APP_PRIVATE_KEY -R leandroAmariles/shoplab-gateway < <ruta-al-pem>`. Si no se conserva el PEM, generar una clave nueva en los ajustes de la App (GitHub no permite leer la ya guardada)
- [X] T004 MANUAL: crear el entorno `dev` en `shoplab-gateway` (Settings → Environments) y confirmar que la GitHub App está instalada en `leandroAmariles/shoplab-infra` con permiso de escritura de contenidos
- [X] T005 Verificar con `gh secret list` y `gh variable list` (`-R leandroAmariles/shoplab-gateway`) que existen `RELEASE_PLEASE_TOKEN`, `DEPLOY_APP_PRIVATE_KEY` y `DEPLOY_APP_ID`; clonar el repo junto a `shoplab-helpdesk` y crear la rama `feature/LAB-107-strangler-gateway`
- [X] T006 Crear `shoplab-gateway/pom.xml` a partir de la plantilla (`specs/LAB-103-reusable-workflows-service-template/shoplab-service-template/pom.xml`): parent Boot 4.1.2, Java 21, BOM `spring-cloud-dependencies` 2025.1.5, `spring-cloud-starter-gateway-server-webflux`, actuator, `micrometer-registry-prometheus`, test (`spring-boot-starter-test`, `reactor-test`, ArchUnit), JaCoCo con puerta de 80 % (excluye `config`, `model`, `exception`), Checkstyle y PMD como en `backend/pom.xml`. Sin R2DBC, JDBC, Flyway, OAuth2, `shoplab-contracts` ni logstash
- [X] T007 [P] Copiar el wrapper de Maven (`backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/`) a `shoplab-gateway/` (el Dockerfile lo necesita) y dejar `mvnw` ejecutable con `git update-index --chmod=+x mvnw`
- [X] T008 [P] Copiar `backend/config/checkstyle.xml`, `backend/config/pmd-ruleset.xml` y `backend/qodana.yaml` a `shoplab-gateway/config/` y `shoplab-gateway/qodana.yaml`
- [X] T009 [P] Crear `shoplab-gateway/Dockerfile` (el de la plantilla, con usuario de UID numérico 10001), `shoplab-gateway/.dockerignore` y `shoplab-gateway/.gitignore`
- [X] T010 [P] Crear `shoplab-gateway/.github/workflows/ci.yml` (el de la plantilla con `image-name: ghcr.io/leandroamariles/gateway`) y `shoplab-gateway/.github/dependabot.yml`
- [X] T011 Crear `MAIN/GatewayApplication.java` y `shoplab-gateway/src/main/resources/application.yml` base (puerto 8080, apagado ordenado, nombre `gateway`, logs estructurados en consola) y comprobar `./mvnw verify`

**Checkpoint**: el proyecto compila y los secretos están cargados.

---

## Phase 2: Foundational (tabla de rutas y pruebas de riesgo)

**Purpose**: enrutamiento mínimo y respuesta a los tres puntos no documentados (research D-5).

**⚠️ CRITICAL**: si T017, T018 o T019 no pueden pasar, detenerse y revisar research D-5 antes de seguir.

- [X] T012 [P] Crear `TEST/support/StubDestination.java`: servidor Reactor Netty en proceso que graba método, ruta, consulta, cabeceras y cuerpo, y emite respuestas configurables (fragmentos SSE con pausas, retraso de cabeceras, corte a mitad, detección de cancelación)
- [X] T013 [P] Escribir `TEST/infrastructure/route/RouteTableValidatorTest.java` con las reglas de data-model.md: `name` "minúsculas y dígitos"; `prefix` "empieza por `/api/`; segmentos `[a-z0-9-]`; sin `/` final ni comodines"; `uri` "`http` o `https`, con host; sin ruta, consulta ni credenciales"; `responseTimeout` "positiva; por defecto 120 s"; conjunto: "al menos una ruta; sin prefijos repetidos; ningún prefijo contiene a otro por segmentos completos". Cada fallo nombra la ruta (FR-010)
- [X] T014 Crear `MAIN/config/GatewayRoutesProperties.java` (`shoplab.gateway.monolith-uri` y mapa `shoplab.gateway.routes.<name>.{prefix,uri,response-timeout}`, sin lógica) y `MAIN/infrastructure/route/RouteTableValidator.java` hasta que T013 pase
- [X] T015 Crear `MAIN/infrastructure/route/GatewayRouteLocatorConfiguration.java`: una ruta por entrada con `Path` `<prefix>` y `<prefix>/**`, conservando `Host`, con `response-timeout` por ruta
- [X] T016 Declarar en `shoplab-gateway/src/main/resources/application.yml` las rutas `chat` (`/api/chat`), `documents` (`/api/documents`), `knowledge` (`/api/knowledge`), `eval` (`/api/eval`) y `assistant` (`/api/assistant`) hacia `${GATEWAY_MONOLITH_URI:http://localhost:8081}`, con `connect-timeout` 2 s, `response-timeout` 120 s, sin compresión ni reintentos
- [X] T017 Escribir y hacer pasar `TEST/streaming/SseFlushTest.java`: cada fragmento llega antes de que el destino emita el siguiente
- [X] T018 Escribir y hacer pasar `TEST/streaming/SseTimeoutTest.java`: con `response-timeout` corto en la prueba, un flujo cuyas cabeceras ya llegaron y que dura más no se corta
- [X] T019 Escribir y hacer pasar `TEST/streaming/SseCancellationTest.java`: al cancelar el cliente, el destino ve cerrada la conexión
- [X] T020 [P] Crear `TEST/architecture/ArchitectureTest.java`: `shared` no depende de `infrastructure`; nada depende de `com.shoplab.helpdesk..`

**Checkpoint**: se enruta al monolito y el streaming funciona.

---

## Phase 3: User Story 1 - Toda la API pasa por el gateway (Priority: P1) 🎯 MVP

**Goal**: paridad con el monolito, errores propios en formato estándar, correlación y observabilidad.

**Independent Test**: contra un destino simulado y contra el monolito local, mismas respuestas por el gateway que directas.

### Tests for User Story 1 ⚠️

- [X] T021 [P] [US1] Escribir `TEST/routing/RoutingContractTest.java` con R1-R6 de contracts/routing.md (incluye `/api/chatbot`, `/api/catalog` y segmentos `.`/`..` literales y codificados que no llegan al destino)
- [X] T022 [P] [US1] Escribir `TEST/routing/TransparencyTest.java`: método, ruta, consulta, cuerpo, `Host`, `Authorization`, `X-Eval-Token`, `X-Prompt-Token`, `X-Prompt-Versions` sin cambios; estados 400, 404, 409, 413, 429 y 503 del destino y sus cuerpos pasan tal cual
- [X] T023 [P] [US1] Escribir `TEST/routing/UploadTest.java`: multipart de 10 MiB llega íntegro; uno mayor, rechazado por el destino antes de leerlo entero, entrega al cliente el error tipado del destino
- [X] T024 [P] [US1] Escribir `TEST/shared/error/GatewayErrorTest.java`: `SLHD_GATE_00001` (404), `00002` (503), `00003` (504), `00004` (500), cuerpo `{code, message, detail:null}` según `ApiErrorResponse`, sin hosts, direcciones ni trazas
- [X] T025 [P] [US1] Escribir `TEST/infrastructure/filter/CorrelationFilterTest.java`: `X-Request-Id` conservado si cumple `^[A-Za-z0-9._-]{1,64}$` y generado si no; llega al destino; vuelve una sola vez aunque el destino también lo ponga; `traceparent` conservado o generado en formato W3C
- [X] T026 [P] [US1] Escribir `TEST/infrastructure/filter/AccessLogFilterTest.java`: una línea con `requestId`, `routeId`, destino, método, estado y duración, sin ruta completa, consulta, cabeceras ni cuerpos
- [X] T027 [P] [US1] Escribir `TEST/ManagementEndpointsTest.java`: `health` (liveness y readiness) y `prometheus` responden; existe `spring.cloud.gateway.requests` con etiqueta `routeId`; la salud sigue `UP` con el destino caído; ningún `/actuator` del destino es alcanzable

### Implementation for User Story 1

- [X] T028 [US1] Crear `MAIN/shared/error/ResponseCodeEnum.java`, `MAIN/shared/error/ApiError.java` y `MAIN/shared/error/GlobalExceptionHandler.java` (`ErrorWebExceptionHandler` con prioridad sobre el de Boot) hasta que T024 pase
- [X] T029 [P] [US1] Crear `MAIN/infrastructure/filter/UnsafePathFilter.java` que responda 404 a segmentos `.` y `..`, también codificados
- [X] T030 [P] [US1] Crear `MAIN/infrastructure/filter/CorrelationFilter.java`, generando ids con `ThreadLocalRandom` (como `backend/.../RequestIdFilter.java`, sin llamadas bloqueantes)
- [X] T031 [P] [US1] Crear `MAIN/infrastructure/filter/AccessLogFilter.java`
- [X] T032 [US1] Configurar en `shoplab-gateway/src/main/resources/application.yml` actuator (`health`, `info`, `prometheus`, sondas) y `spring.cloud.gateway.server.webflux.metrics.enabled: true`
- [X] T033 [US1] Ajustar lo necesario hasta que T021, T022 y T023 pasen y `./mvnw verify` quede verde

**Checkpoint**: el gateway sustituye al monolito como puerta de la API.

---

## Phase 4: User Story 2 - El streaming del chat no se retiene (Priority: P1)

**Goal**: completar el comportamiento de streaming sobre la base de T017-T019.

**Independent Test**: destino simulado con pausas conocidas; chat real por el gateway.

### Tests for User Story 2 ⚠️

- [X] T034 [P] [US2] Escribir `TEST/streaming/SseFailureTest.java`: corte del destino a mitad deja el flujo interrumpido, nunca cerrado como completo; el evento de error del destino pasa tal cual
- [X] T035 [P] [US2] Escribir `TEST/streaming/SseHeadersAndLatencyTest.java`: `X-Accel-Buffering: no` y `text/event-stream` llegan al cliente, sin compresión; retraso añadido por fragmento < 200 ms (SC-002)

### Implementation for User Story 2

- [X] T036 [US2] Ajustar `shoplab-gateway/src/main/resources/application.yml` y los filtros de `MAIN/infrastructure/filter/` hasta que T034 y T035 pasen sin romper T017-T019

**Checkpoint**: el chat se comporta igual que sin gateway.

---

## Phase 5: User Story 3 - Mover una ruta solo con configuración (Priority: P1)

**Goal**: demostrar y documentar el estrangulamiento ruta a ruta.

**Independent Test**: dos destinos distinguibles; cambiar una variable y ver que solo se mueve ese prefijo.

### Tests for User Story 3 ⚠️

- [X] T037 [P] [US3] Escribir `TEST/routing/RouteMoveTest.java` (FR-020): mover `documents` con configuración, el resto sigue en el monolito, la petición llega idéntica, revertir la devuelve; `documents` y `knowledge` se mueven por separado
- [X] T038 [P] [US3] Escribir `TEST/routing/EnvironmentRouteTest.java`: `SHOPLAB_GATEWAY_ROUTES_DOCUMENTS_URI` mueve la ruta y `SHOPLAB_GATEWAY_ROUTES_CATALOG_PREFIX` + `_URI` dan de alta `/api/catalog` sin tocar `application.yml` (FR-006)

### Implementation for User Story 3

- [X] T039 [US3] Ajustar `MAIN/config/GatewayRoutesProperties.java` y `MAIN/infrastructure/route/` hasta que T037 y T038 pasen
- [X] T040 [US3] Escribir `docs/fases/F5-mover-rutas-al-gateway.md`: mover, dar de alta y revertir una ruta en local y en dev, con los ejemplos de contracts/configuration.md

**Checkpoint**: LAB-129 puede usar el gateway.

---

## Phase 6: User Story 4 - El gateway corre en local y en dev (Priority: P2)

**Goal**: publicar `v0.1.0`, integrarlo en el entorno local y desplegarlo en dev.

**Independent Test**: compose completo en local; `gateway-dev` Synced y Healthy y la web usando la API por el gateway.

### Publicación

- [X] T041 [P] [US4] Crear `shoplab-gateway/release-please-config.json` (`release-type: maven`, `package-name: gateway`, `initial-version: 0.1.0`, `bump-minor-pre-major: true`, `skip-snapshot: true`), `shoplab-gateway/.release-please-manifest.json` y `shoplab-gateway/.github/workflows/release-please.yml` (como el de este repo, con `RELEASE_PLEASE_TOKEN`)
- [X] T042 [P] [US4] Crear `shoplab-gateway/.github/workflows/release.yml` en tags `v*.*.*`: job `publish` con `shoplab-ci/.github/workflows/publish-image.yml@v1` (`image-name: ghcr.io/leandroamariles/gateway`; permisos `packages: write`, `id-token: write`) y job `deploy` (`needs: publish`) con `deploy.yml@v1`, `service-name: gateway`, `overlay: apps/gateway/overlays/dev`, `vars.DEPLOY_APP_ID` y `secrets.DEPLOY_APP_PRIVATE_KEY`
- [X] T043 [P] [US4] Escribir `shoplab-gateway/README.md`: propósito, variables, rutas, cómo probar y publicar
- [X] T044 [US4] APROBACIÓN: abrir el PR en `shoplab-gateway` (`feat(gateway): [LAB-107] strangler gateway`), dejar `ci.yml` verde y fusionarlo. Es el primer servicio Java que usa `shoplab-ci@v1`: si falla por el workflow reutilizable, detenerse e informar
- [X] T045 [US4] APROBACIÓN: fusionar el PR de release-please y comprobar con `gh run watch` que el tag `v0.1.0` publica `ghcr.io/leandroamariles/gateway:v0.1.0`. El job `deploy` fallará esta primera vez porque el overlay aún no existe (se relanza en T060)
- [X] T046 [US4] MANUAL / APROBACIÓN: hacer **público** el paquete en GitHub → perfil → Packages → `gateway` → Package settings → Change visibility → Public. **No es reversible**: un paquete público no puede volver a ser privado
- [X] T047 [US4] Verificar el acceso anónimo en una sesión sin credenciales: `docker logout ghcr.io` y `docker buildx imagetools inspect ghcr.io/leandroamariles/gateway:v0.1.0`

### Entorno local

- [X] T048 [P] [US4] Crear `infra/frontend/nginx.conf`: la configuración de `shoplab-frontend/nginx.conf` (puerto 8080) más `location /api/` hacia `http://gateway:8080` con `proxy_buffering off`, HTTP/1.1, `client_max_body_size 12m` y `proxy_read_timeout 120s`
- [X] T049 [US4] Modificar `infra/docker-compose.yml`: servicio `gateway` (`${GATEWAY_IMAGE}`, `${GATEWAY_PORT:-8080}:8080`, `GATEWAY_MONOLITH_URI=http://backend:8080`, healthcheck); `backend` publicado en `${BACKEND_PORT:-8081}:8080`; `frontend` en `${FRONTEND_PORT:-4200}:8080` con el nginx montado, healthcheck al 8080 y dependencia de `gateway`; imágenes por defecto `ghcr.io/leandroamariles/{backend,frontend,ingest}` con una versión publicada que exista
- [X] T050 [P] [US4] Actualizar `infra/.env.example`: `GATEWAY_IMAGE=ghcr.io/leandroamariles/gateway:v0.1.0`, `GATEWAY_PORT=8080`, `BACKEND_PORT=8081`, nombres y versiones de imagen corregidos
- [X] T051 [P] [US4] Añadir `gateway` (build de `../../shoplab-gateway`) a `infra/docker-compose.override.yml.example`
- [X] T052 [P] [US4] Añadir `shoplab-gateway` a la lista de repos de `scripts/clone-all.ps1` y de `Makefile`
- [X] T053 [P] [US4] Actualizar `scripts/test-e2e.sh` y `scripts/test-e2e.ps1`: backend en `:8081`, comprobar salud del gateway y `GET http://localhost:8080/api/documents`
- [X] T054 [P] [US4] Actualizar `infra/observability/prometheus/prometheus.yml`: backend en el 8081 (sin cambiar el nombre del job) y job nuevo `gateway`
- [X] T055 [US4] Buscar otras referencias a `localhost:8080` que signifiquen «monolito» (`README.md`, `docs/`, `infra/`, `scripts/`) y actualizarlas; documentar en `README.md` `SERVER_PORT=8081` para el IDE y el cambio de `BACKEND_PORT` en un `infra/.env` ya existente
- [X] T056 [US4] Validar los pasos 2 y 5 de quickstart.md con `docker compose -f infra/docker-compose.yml up -d --wait`

### Dev

- [X] T057 [US4] Con el clúster arriba (`make up` en `shoplab-infra`), comprobar si `shoplab-dev` y `backend-dev` compiten por el Deployment `backend` (`kubectl -n argocd get applications` y las anotaciones del Deployment). Si compiten, informar y acordar la solución antes de T059
- [X] T058 [P] [US4] En una rama desde `origin/main` de `shoplab-infra`, crear `shoplab-infra/apps/gateway/base/{deployment,service,servicemonitor,kustomization}.yaml` (Service `gateway`, puerto `http` 8080; `runAsUser` 10001, raíz de solo lectura, `/tmp` en `emptyDir`, sondas, recursos; sin ExternalSecret) y `shoplab-infra/apps/gateway/overlays/dev/kustomization.yaml` (namespace `shoplab-dev`, `configMapGenerator` `gateway-config` con `GATEWAY_MONOLITH_URI=http://backend:8080`, `images:` con `- name: ghcr.io/leandroamariles/gateway` y `newTag: v0.1.0`)
- [X] T059 [US4] APROBACIÓN: validar con `kustomize build apps/gateway/overlays/dev` y `scripts/policy-check.sh`, abrir y fusionar el PR de alta (sin tráfico) y comprobar `gateway-dev` Synced y Healthy
- [X] T060 [US4] APROBACIÓN: relanzar el job fallido de T045 con `gh run rerun <run-id> --failed -R leandroAmariles/shoplab-gateway` y comprobar que el despliegue termina sin tocar otras imágenes
- [X] T061 [US4] Preparar el cambio de `shoplab-infra/k8s/base/ingress/ingress.yaml` (`/api` → Service `gateway`; `/` y `proxy-body-size` sin cambios), el texto de `shoplab-infra/scripts/forward.sh` y `shoplab-infra/docs/gitops.md`
- [X] T062 [US4] APROBACIÓN: fusionar el corte del ingress y validar el paso 8 de quickstart.md (chat progresivo en el navegador, documentos, evaluaciones, logs del gateway). Si algo falla, revertir ese commit — validado por HTTP; falta el recorrido en el navegador
- [X] T063 [US4] Medir SC-004 en dev: cambiar y revertir un valor de `gateway-config` y comprobar que cada cambio está activo en menos de 10 minutos

**Checkpoint**: dev y local sirven la API por el gateway.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T064 [P] Escribir `docs/adr/0032-gateway-estrangulador.md` (plantilla `docs/adr/0000-plantilla.md`: decisión, puertos locales, desviaciones de la plantilla, alternativas) y añadirlo a `docs/adr/README.md`
- [X] T065 [P] Actualizar `docs/mapa-servicios.md`: prefijos reales (`/api/documents`, `/api/knowledge`, `/api/assistant`) en las secciones de `retrieval-service` y `gateway`; `/` lo sirve el frontend, no el gateway
- [X] T066 [P] Crear `specs/LAB-107-strangler-gateway/requests.http` (`baseUrl=http://localhost:8080`) con un caso por requisito y escenario alcanzable por HTTP y la lista de los no cubiertos
- [X] T067 [P] Anotar en `specs/LAB-107-strangler-gateway/validation.md` los fallos de la plantilla de LAB-103 (no publica `:vX.Y.Z`, exige JWT, faltan `mvnw` y `.mvn`) para abrir una historia de corrección
- [X] T068 Validar la paridad (quickstart.md pasos 3, 4, 6 y 7: SC-001, SC-003, SC-006) y anotar resultados en `specs/LAB-107-strangler-gateway/validation.md`
- [X] T069 APROBACIÓN: abrir el PR de `shoplab-helpdesk` (`feat(gateway): [LAB-107] gateway estrangulador en entorno local y documentación`) con CI verde

---

## Dependencies & Execution Order

### Phase Dependencies

```text
Setup ─▶ Foundational ─▶ US1 ─▶ US2 ─▶ US3 ─▶ US4 ─▶ Polish
```

- US2 y US3 solo dependen de Foundational; pueden ir en paralelo con US1.
- US4 necesita US1, US2 y US3 fusionadas (T044 publica el código completo).

### Cadenas que no admiten otro orden

- T001 → T002, T003, T004 → T005
- T044 → T045 → T046 → T047 → T059 → T060
- T045 → T049, T050 (la imagen debe existir) y T046 → T056
- T057 → T059 → T062; T061 → T062
- T048 → T049 → T056

### Within Each User Story

Pruebas primero y en rojo; después el código; cerrar con `./mvnw verify`.

## Parallel Opportunities

```text
Setup:   T007, T008, T009, T010
US1:     T021-T027 (pruebas), luego T029, T030, T031
US2:     T034, T035
US3:     T037, T038
US4:     T041, T042, T043  |  T048, T050, T051, T052, T053, T054
Polish:  T064, T065, T066, T067
```

## Implementation Strategy

### MVP

Setup + Foundational + US1: un gateway probado que enruta todo al monolito. No se despliega solo: antes de poner tráfico
real hace falta US2 (el chat) y para que sirva a LAB-129 hace falta US3.

### Entrega incremental

1. Un PR en `shoplab-gateway` con Setup a US3 y los archivos de publicación.
2. Release `v0.1.0` y paquete público.
3. PR de `shoplab-infra`: alta sin tráfico.
4. PR de `shoplab-infra`: corte del ingress (reversible).
5. PR de `shoplab-helpdesk`: local, documentación y spec.

## Notes

- Commit por tarea o grupo lógico. Nada se fusiona ni publica sin aprobación.
- No editar a mano `CHANGELOG.md` ni la versión.
