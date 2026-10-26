# Tasks: Métricas base con Prometheus y Grafana

**Input**: Design documents from `/specs/LAB-102-base-metrics-prometheus-grafana/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: se piden (constitución, principio IV, test-first): prueba de `MockMvc` del backend, `terraform test`
del módulo `platform`, prueba de la regla con `promtool` y el job de CI que valida los dashboards. Cada prueba se
escribe antes que el código que la satisface y se comprueba que falla.

**Organization**: tareas agrupadas por historia de usuario. Las cuatro primeras son P1 y la última P2. El orden de
las fases no es el numérico de `spec.md`: **US1 → US2 → US4 → US3 → US5**, porque los dashboards (US4) se
construyen sobre el entorno de compose (US2) y el clúster (US3) los consume. Dos repositorios:

- `HD` = `shoplab-helpdesk` (este repositorio; rutas relativas a su raíz).
- `INF` = `shoplab-infra` (clon hermano en `../shoplab-infra`; rutas relativas a su raíz).

Rutas, nombres y valores exactos: [contracts/](contracts/) y [data-model.md](data-model.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia de `spec.md` a la que pertenece (US1…US5)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: línea base y cierre de los puntos «a confirmar» de [research.md](research.md) antes de escribir código.

- [X] T001 En `HD`, con la rama `feature/LAB-102-base-metrics-prometheus-grafana` sin cambios de código, ejecutar `./mvnw -B -ntp clean verify` en `backend/` y anotar el resultado y el porcentaje de cobertura JaCoCo como línea base en `specs/LAB-102-base-metrics-prometheus-grafana/validation.md` (créalo con una sección «Línea base»)
- [X] T002 [P] Averiguar las versiones de Prometheus y Grafana que despliega `kube-prometheus-stack` 92.13.1 (`helm template kube-prometheus-stack --repo https://prometheus-community.github.io/helm-charts --version 92.13.1 | grep -E "image:"`), comprobar que los tags existen en sus registros y registrar en `research.md` D3 los tags exactos elegidos para `PROMETHEUS_IMAGE`, `GRAFANA_IMAGE` y `PROMTOOL_VERSION` (sin `latest`)
- [X] T003 [P] Confirmar que el catálogo de CRDs que usa `kubeconform` (`https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/monitoring.coreos.com/servicemonitor_v1.json` y `prometheusrule_v1.json`) responde con esquema; si falta alguno, anotar en `research.md` D9 la alternativa (esquema local versionado) antes de T030
- [X] T004 [P] Confirmar en Spring Boot 4.1.2 la anotación o propiedad que reactiva la exportación de métricas en `@SpringBootTest` (buscar `AutoConfigureObservability` en `~/.m2/repository/org/springframework/boot/` o la propiedad equivalente) y anotar el nombre exacto y su paquete en `research.md` D2, sustituyendo el «a confirmar»

---

## Phase 2: Foundational (Blocking Prerequisites)

Sin tareas bloqueantes: cada historia toca archivos distintos. La única dependencia compartida es el directorio
`infra/observability/` de `HD`, que se crea con la primera tarea que lo necesita (T012) y lo reutilizan US2, US4 y US3.

---

## Phase 3: User Story 1 - El backend publica sus métricas (Priority: P1) 🎯 MVP

**Goal**: `GET /actuator/prometheus` devuelve métricas de JVM y HTTP con la etiqueta `application` y el histograma
de latencia, sin exponer otros endpoints y sin cambiar `health`.

**Independent Test**: `./mvnw -B -ntp clean verify` pasa (incluye `MetricsEndpointTests`) y, con el backend
arrancado, `curl localhost:8080/actuator/prometheus` muestra las series del contrato
([contracts/metrics-endpoint.md](contracts/metrics-endpoint.md)).

### Tests for User Story 1 ⚠️ escribir primero y comprobar que fallan

- [X] T005 [US1] Crear `backend/src/test/java/com/shoplab/helpdesk/MetricsEndpointTests.java` (`@SpringBootTest` con puerto aleatorio y `java.net.http.HttpClient`: `MockMvc` no está en el classpath del proyecto y la exportación de métricas no se desactiva en Boot 4.1.2, ver research D2) con las 6 pruebas de la tabla de [contracts/metrics-endpoint.md](contracts/metrics-endpoint.md): (1) `200` con `jvm_memory_used_bytes` y `http_server_requests_seconds_count`; (2) tras una petición aparece `http_server_requests_seconds_bucket`; (3) las series llevan `application="helpdesk-backend"`; (4) `/actuator/health` responde `200` con `UP`; (5) dos peticiones con ids distintos a una ruta con variable producen **una** sola serie con `uri="/probe/{id}"`; (6) `/actuator/env` responde `404`. La ruta con variable es una clase estática anidada `@RestController` importada con `@Import`, no una clase nueva en `src/main`. Identificadores en inglés, líneas ≤ 121 caracteres, llaves en todos los bloques
- [X] T006 [US1] Ejecutar `./mvnw -B -ntp test -Dtest=MetricsEndpointTests` en `backend/` y comprobar que las pruebas 1, 2, 3, 5 y 6 **fallan** (no hay registro de Prometheus ni exposición; `health` y `env` pasan como pruebas de guarda); anotar el resultado en `validation.md`

### Implementation for User Story 1

- [X] T007 [US1] Añadir en `backend/pom.xml`, junto a `spring-boot-starter-actuator`, la dependencia `io.micrometer:micrometer-registry-prometheus` sin versión (la gestiona el BOM de Boot)
- [X] T008 [US1] En `backend/src/main/resources/application.yml` cambiar `management.endpoints.web.exposure.include` a `health,prometheus`, añadir `management.metrics.tags.application: ${spring.application.name}` y `management.metrics.distribution.percentiles-histogram.http.server.requests: true` (bloque exacto en [contracts/metrics-endpoint.md](contracts/metrics-endpoint.md)); no usar `*`
- [X] T009 [US1] Ejecutar `./mvnw -B -ntp clean verify` en `backend/`: las 6 pruebas pasan, `ModularityTests` y `ShopLabHelpdeskApplicationTests` siguen verdes, Checkstyle y PMD sin violaciones y JaCoCo ≥ 80 % (no menor que la línea base de T001)
- [X] T010 [US1] Arrancar el backend (`./mvnw spring-boot:run`, con el Postgres de compose si hace falta) y ejecutar el paso A de [quickstart.md](quickstart.md): series presentes, `health` en `UP`, `/actuator/env` en `404`; anotar en `validation.md`
- [X] T011 [US1] Generar `specs/LAB-102-base-metrics-prometheus-grafana/requests.http` (regla de `.ai/rules/http-test-requests.md`, `baseUrl` = `http://localhost:8080`) con un caso por escenario 1.1-1.5 del spec, el `404` de `/actuator/env` y un bloque «Not covered here» para US2-US5 (no alcanzables por HTTP del backend); cada petición lleva su trazabilidad (`### FR-001 / Scenario …`) y el resultado esperado

**Checkpoint**: US1 completa y verificable sola. Es el MVP: el backend ya publica métricas aunque nadie las recoja.

---

## Phase 4: User Story 2 - Ver métricas en local con Prometheus y Grafana (Priority: P1)

**Goal**: `docker compose --profile observability up` arranca Prometheus y Grafana con imágenes fijadas; Prometheus
recoge el backend del host y Grafana arranca con el origen de datos aprovisionado.

**Independent Test**: sin el perfil no arranca nada nuevo; con el perfil y el backend en marcha, el objetivo
`helpdesk-backend` está `up` y Grafana responde `ok` con su origen de datos ya creado (paso B de
[quickstart.md](quickstart.md)).

- [X] T012 [P] [US2] Crear `infra/observability/prometheus/prometheus.yml` en `HD`: `scrape_interval: 15s`, job `helpdesk-backend`, `metrics_path: /actuator/prometheus`, objetivo `host.docker.internal:8080` (comentario sobre el puerto fijo, research D4)
- [X] T013 [P] [US2] Crear `infra/observability/grafana/provisioning/datasources/prometheus.yml` en `HD`: datasource `Prometheus` por defecto, `url: http://prometheus:9090`, sin `uid` fijo del que dependan los dashboards
- [X] T014 [P] [US2] Crear `infra/observability/grafana/provisioning/dashboards/dashboards.yml` en `HD`: proveedor «ShopLab» (carpeta «ShopLab») que lee `/var/lib/grafana/dashboards`, `allowUiUpdates: false`
- [X] T015 [US2] Editar `infra/docker-compose.yml` en `HD`: servicios `prometheus` y `grafana` con `profiles: [observability]`, imágenes `${PROMETHEUS_IMAGE:-…}`/`${GRAFANA_IMAGE:-…}` con los tags de T002, puertos `${PROMETHEUS_PORT:-9090}` y `${GRAFANA_PORT:-3000}`, volúmenes `prometheus_data` y `grafana_data` (declarar en `volumes:`), `extra_hosts: host.docker.internal:host-gateway`, `healthcheck` con `wget --spider` y `start_period`, `restart: unless-stopped`, `GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-change-me-local-only}` y `grafana` con `depends_on: prometheus: condition: service_healthy`; actualizar la cabecera (el perfil `observability` deja de ser «reservado»). Disposición exacta en [contracts/observability-layout.md](contracts/observability-layout.md)
- [X] T016 [US2] Actualizar `README.md` en `HD`: la fila de `observability` (línea ~119) pasa de «Reservado (F6)» a sus servicios, con las URLs `http://localhost:9090` y `http://localhost:3000`, las variables nuevas y la nota de que el backend se recoge en el puerto 8080 del host
- [X] T017 [US2] Validar en `HD` los escenarios 2.1-2.5: `docker compose -f infra/docker-compose.yml config` con y sin `--profile observability`; `up -d --wait` sin el perfil (0 contenedores nuevos, SC-005) y con el perfil (ambos `healthy`); objetivo `helpdesk-backend` en `up` en `/api/v1/targets`; origen de datos de Grafana presente sin pasos manuales; backend detenido → `up == 0` sin que Prometheus ni Grafana fallen. Anotar en `validation.md`

**Checkpoint**: US1 y US2 funcionan; Grafana aún sin dashboards (los trae US4).

---

## Phase 5: User Story 4 - Dashboards versionados como código (Priority: P1)

**Goal**: tres dashboards JSON (JVM, HTTP, pods) en git, aprovisionados en compose, con validación automática en CI.

**Independent Test**: borrar los volúmenes de Grafana (`down -v`), volver a levantar y encontrar los tres dashboards
con datos; una pull request con un JSON inválido falla en CI (paso B y escenario 4.6).

### Tests for User Story 4 ⚠️ escribir primero

- [X] T018 [US4] Crear `infra/observability/check-dashboards.sh` en `HD` (bash con Python embebido, `set -euo pipefail`, acepta un directorio como argumento y por defecto `infra/observability/dashboards`; Python en lugar de `jq`, que no está en todas las máquinas): falla si un JSON es inválido, si falta `uid` o `title`, si hay `uid` o `title` repetidos entre archivos, si no existe la variable `datasource` de tipo `datasource` o si algún panel fija un `uid` de datasource concreto (reglas de [data-model.md](data-model.md) «Dashboard»)
- [X] T019 [US4] Comprobar que el script falla donde debe: directorio vacío, y copias temporales (en el directorio scratchpad, no en el repositorio) con JSON inválido, `uid` duplicado y sin variable `datasource`; anotar los casos en `validation.md`

### Implementation for User Story 4

- [X] T020 [P] [US4] Crear `infra/observability/dashboards/jvm.json` en `HD`: `uid: shoplab-jvm`, título «ShopLab · JVM», variables `datasource` y `application` (por defecto `helpdesk-backend`); paneles de heap usada/máxima, hilos vivos, pausas de recolección (tasa y máximo) y CPU del proceso con las series de [contracts/metrics-endpoint.md](contracts/metrics-endpoint.md)
- [X] T021 [P] [US4] Crear `infra/observability/dashboards/http.json` en `HD`: `uid: shoplab-http`, título «ShopLab · HTTP», variables `datasource` y `application`; paneles de tasa de peticiones por ruta, latencia p95 por ruta con `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{application="$application"}[5m])))`, tasa de errores 5xx y peticiones por estado
- [X] T022 [P] [US4] Crear `infra/observability/dashboards/pods.json` en `HD`: `uid: shoplab-pods`, título «ShopLab · Pods», variables `datasource`, `namespace` (por defecto `shoplab-dev`) y `pod`; paneles de estado (`kube_pod_status_phase`), reinicios (`kube_pod_container_status_restarts_total`), pods listos (`kube_pod_status_ready`) y CPU/memoria del contenedor; en compose mostrará «sin datos» (esperado)
- [X] T023 [US4] Ejecutar `infra/observability/check-dashboards.sh` sobre los tres JSON y confirmar que pasa
- [X] T024 [US4] Añadir en `.github/workflows/ci.yml` de `HD` el filtro `observability` (`infra/observability/**`) en el job `changes`, el job `observability` (checkout, Python ya instalado en `ubuntu-latest`, `bash infra/observability/check-dashboards.sh`, `docker compose -f infra/docker-compose.yml --profile observability config -q`) y su inclusión en el `needs` y la evaluación de `ci-gate` sin cambiar el nombre del gate (`ci-gate` es la verificación que exige la protección de `main`)
- [X] T025 [US4] Validar en `HD` los escenarios 4.1-4.5 con el paso B de [quickstart.md](quickstart.md): borrar solo los servicios y volúmenes de observabilidad (`rm -sfv prometheus grafana` y `docker volume rm shoplab_prometheus_data shoplab_grafana_data`; nunca `down -v`, que borraría Postgres y Ollama) y volver a levantar → aparecen `shoplab-jvm`, `shoplab-http` y `shoplab-pods` sin importar nada; con tráfico (`curl` en bucle a `/actuator/health`) los paneles de JVM y HTTP muestran datos en menos de 2 minutos (SC-001); un cambio hecho en la interfaz se pierde al reiniciar Grafana. Anotar en `validation.md`

**Checkpoint**: US1, US2 y US4 completas: el flujo local entero funciona y está validado en CI.

---

## Phase 6: User Story 3 - Ver métricas del backend y del clúster en Kubernetes (Priority: P1)

**Goal**: en el clúster local, Prometheus recoge el backend mediante un `ServiceMonitor`, Alertmanager está activo y
Grafana carga los mismos dashboards desde `shoplab-helpdesk`.

**Independent Test**: tras `make up` en `INF` con el backend desplegado, el objetivo `backend` está `up` en Prometheus,
`shoplab-dev` y `shoplab-observability` están `Synced`/`Healthy` y los 3 dashboards muestran datos (paso C de
[quickstart.md](quickstart.md)).

### Tests for User Story 3 ⚠️ escribir primero

- [X] T026 [US3] En `INF`: `git switch main && git pull --ff-only`, comprobar árbol limpio y crear la rama `feature/LAB-102-base-metrics-prometheus-grafana`
- [X] T027 [US3] Leer `terraform/modules/platform/tests/platform.tftest.hcl` y `terraform/envs/dev/tests/apply.tftest.hcl` en `INF` y añadir al primero, siguiendo su patrón, aserciones de que los valores de `kube-prometheus-stack` activan Alertmanager (`alertmanager.enabled == true` con requests `10m`/`32Mi`) y fijan `serviceMonitorSelectorNilUsesHelmValues` y `ruleSelectorNilUsesHelmValues` en `false`; ejecutar `terraform test` en el módulo y comprobar que **falla**; revisar si `apply.tftest.hcl` necesita el mismo ajuste

### Implementation for User Story 3

- [X] T028 [P] [US3] Crear `infra/observability/kustomization.yaml` en `HD`: `namespace: monitoring`, un `configMapGenerator` por dashboard (`shoplab-dashboard-jvm`, `-http`, `-pods`) con `files: dashboards/<nombre>.json`, `labels` `grafana_dashboard: "1"` en los ConfigMaps y `generatorOptions.disableNameSuffixHash: true`; el archivo está en la raíz de `infra/observability/` para no usar `--load-restrictor` (research D5)
- [X] T029 [US3] Añadir al job `observability` de `.github/workflows/ci.yml` en `HD` el paso `kubectl kustomize infra/observability` que falla si no rinde un `ConfigMap` con la etiqueta `grafana_dashboard: "1"` por cada JSON de `infra/observability/dashboards/`; ejecutarlo en local y anotar el resultado
- [X] T030 [US3] En `INF`, editar `terraform/modules/platform/monitoring.tf`: sustituir `alertmanager = { enabled = false }` por Alertmanager habilitado con `resources.requests` `10m`/`32Mi` y sin persistencia, y añadir a `prometheus.prometheusSpec` `serviceMonitorSelectorNilUsesHelmValues = false` y `ruleSelectorNilUsesHelmValues = false`; ajustar los comentarios del bloque (ya no es «base reducida» sin alertas); `terraform fmt` y `terraform test` en el módulo pasan
- [X] T031 [P] [US3] Crear `k8s/base/backend/servicemonitor.yaml` en `INF`: `ServiceMonitor/backend`, `selector.matchLabels: app.kubernetes.io/name: backend`, endpoint `port: http`, `path: /actuator/prometheus`, `interval: 30s` (datos en [data-model.md](data-model.md)); añadirlo a `resources` de `k8s/base/backend/kustomization.yaml`
- [X] T032 [P] [US3] Crear `k8s/argocd/application-observability.yaml` en `INF` (Application `shoplab-observability`: `repoURL: https://github.com/leandroAmariles/shoplab-helpdesk`, `path: infra/observability`, `targetRevision: main`, destino `monitoring`, finalizer `resources-finalizer.argocd.argoproj.io`, `automated: prune + selfHeal`, `retry` como `application-dev.yaml` y `syncOptions: [ServerSideApply=true]` por el límite de 256 KiB de la anotación de `kubectl apply`) y añadirla a `resources` de `k8s/argocd/kustomization.yaml`
- [X] T033 [US3] En `INF`, editar `versions.env`: `ARGOCD_PRESENT_APPS=shoplab-dev,shoplab-observability` (lista separada por comas, formato que lee `scripts/smoke.sh`); ejecutar `make check` y confirmar que `kustomize`, `kubeconform` (esquemas de T003), políticas y pruebas de políticas pasan con el `ServiceMonitor` y la nueva Application
- [X] T034 [US3] Con la PR de `HD` fusionada y el backend nuevo desplegado en `dev` por LAB-81, en `INF` ejecutar `make up` desde cero y los pasos C y E de [quickstart.md](quickstart.md): objetivo `backend` `up` (uno por réplica), `shoplab-dev` y `shoplab-observability` `Synced`, 3 dashboards con datos, y tras `make down && make up` todo reaparece sin pasos manuales. Anotar en `validation.md` de `HD`

**Checkpoint**: US1-US4 funcionan en compose y en el clúster local.

---

## Phase 7: User Story 5 - Alerta de ejemplo: backend caído (Priority: P2)

**Goal**: la regla `BackendDown` se versiona, se prueba con `promtool`, llega a Alertmanager cuando el backend cae y
se resuelve sola.

**Independent Test**: `make check` en `INF` ejecuta `promtool test rules` y pasa; con el backend a 0 réplicas, la
alerta aparece en Alertmanager en menos de 5 minutos (paso D de [quickstart.md](quickstart.md)).

### Tests for User Story 5 ⚠️ escribir primero

- [X] T035 [US5] En `INF`, fijar `PROMTOOL_VERSION` en `versions.env` (misma versión de Prometheus que T002) y añadir la instalación de `promtool` a `scripts/ci-install-tools.sh` (descarga del tarball de Prometheus, verificación sha256 contra `sha256sums.txt` como las demás herramientas), a `scripts/check-tools.sh` y al job de CI que instala herramientas en `.github/workflows/ci.yml` (leer primero cómo lo hacen `kubeconform` y `yq`)
- [X] T036 [P] [US5] Crear `rules-tests/backend-down.test.yaml` en `INF` con los 5 casos de [contracts/backend-down-alert.md](contracts/backend-down-alert.md): `up == 0` sostenido (dispara a los 3 min, solo pendiente al minuto 1), serie ausente (dispara), corte breve `0 0 1 1 1 1` (no dispara), backend sano (no dispara) y otro namespace caído (no dispara)
- [X] T037 [US5] Crear `scripts/prom-rules.sh` en `INF` (usa `lib.sh`; renderiza `k8s/overlays/dev`, extrae con `yq` el `.spec` de la `PrometheusRule` a un archivo temporal, ejecuta `promtool check rules` y `promtool test rules rules-tests/*.test.yaml`), añadir el objetivo `prom-rules` al `Makefile` (en `.PHONY` y como dependencia de `check`) y ejecutarlo: debe **fallar** porque la regla aún no existe

### Implementation for User Story 5

- [X] T038 [US5] Crear `k8s/base/backend/prometheusrule.yaml` en `INF` con el contenido de [contracts/backend-down-alert.md](contracts/backend-down-alert.md) (`BackendDown`, `expr` con `or absent(...)`, `for: 2m`, `severity: critical`, anotaciones en español) y añadirlo a `resources` de `k8s/base/backend/kustomization.yaml`
- [X] T039 [US5] Ejecutar `make check` en `INF`: `promtool` valida y prueba la regla, y `kustomize`, `kubeconform` y las políticas pasan con el `PrometheusRule`
- [X] T040 [US5] Validar en vivo los escenarios 5.1-5.5 con el paso D de [quickstart.md](quickstart.md): regla cargada e `inactive`; con `scale --replicas=0` la alerta `BackendDown` (`critical`) está activa en Alertmanager en menos de 5 minutos; al restaurar se resuelve sola; un `kubectl rollout restart` no la dispara; tras `make down && make up` la regla reaparece. Anotar en `validation.md`

**Checkpoint**: las cinco historias están completas.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: decisión registrada, documentación, validación final y entrega en dos pull requests.

- [X] T041 [P] Crear `docs/adr/0009-observabilidad-base-fuente-unica-de-dashboards.md` en `HD` con la plantilla `docs/adr/0000-plantilla.md` (decisiones D5 y D7 de [research.md](research.md): una `Application` que lee los dashboards desde `shoplab-helpdesk`, Alertmanager activado, alternativas descartadas y consecuencias, incluido el orden de fusión), enlazarlo desde `docs/adr/README.md`; ADR-0006 no se edita (un ADR aceptado solo cambia su línea de estado al ser sustituido, y este lo complementa)
- [X] T042 [P] Crear `docs/observabilidad.md` en `HD` (FR-023, en español): activar el perfil, URLs y credenciales locales de Prometheus, Grafana y Alertmanager, cómo añadir o cambiar un dashboard (solo por pull request, `uid` estable), cómo probar la alerta, qué muestra cada dashboard, el límite del puerto fijo 8080 en compose y que `/actuator/prometheus` no sale por el ingress; enlazarlo desde `README.md`
- [X] T043 [P] Crear `docs/observability.md` en `INF` (en español, nombre del repositorio) con Alertmanager, los selectores abiertos, el `ServiceMonitor`, la regla, la Application `shoplab-observability` y `make prom-rules`; enlazarlo desde `README.md` y `docs/gitops.md`
- [X] T044 Completar `specs/LAB-102-base-metrics-prometheus-grafana/validation.md` en `HD`: resultados de los pasos A-E de [quickstart.md](quickstart.md) con comandos y salidas, versiones fijadas (T002) y desviaciones; pasar `Status` del spec a `Implemented` y marcar como confirmados los puntos «a confirmar» de `research.md`
- [X] T045 Lista previa al commit de `HD` (`.ai/rules/development-guidelines.md` §8): `./mvnw -B -ntp clean verify`, Checkstyle, JaCoCo ≥ 80 %, `git status --short` sin archivos sueltos (`target/`, `*.log`, dumps) y `git diff --staged` solo con cambios intencionales; en `INF`, `make check` en verde
- [X] T046 Commits atómicos y dos pull requests con la skill `commit-and-push`: primero `HD` con título `feat(observability): [LAB-102] métricas base con Prometheus y Grafana`, y después `INF` con `feat(observability): [LAB-102] activa Alertmanager y descubre el backend`; sin tocar `CHANGELOG.md`, `.release-please-manifest.json` ni la versión. No cerrar LAB-102 en Jira hasta validar el despliegue continuo de la PR de `HD`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T002-T004 se hacen en paralelo y desbloquean T005, T015, T033 y T035.
- **Foundational (Phase 2)**: vacía.
- **US1 (Phase 3)**: tras Setup (T004 para T005). Es el MVP y no depende de ninguna otra historia.
- **US2 (Phase 4)**: tras T002; usa US1 para ver datos reales, pero sus archivos son independientes.
- **US4 (Phase 5)**: tras US2 (se valida en el entorno de compose) y US1 (series reales para los paneles).
- **US3 (Phase 6)**: la parte de `HD` (T028-T029) tras US4 (necesita los JSON); la de `INF` puede empezar tras Setup, pero T034 exige la PR de `HD` fusionada y el backend desplegado.
- **US5 (Phase 7)**: tras T031 (comparte `k8s/base/backend/kustomization.yaml`) y T030 (Alertmanager); T040 exige T034.
- **Polish (Phase 8)**: tras las historias deseadas; T044-T046 al final.

### Dentro de cada historia

- Pruebas primero y en rojo (T005-T006, T018-T019, T027, T035-T037) antes de la implementación.
- Dos tareas que editan `k8s/base/backend/kustomization.yaml` (T031 y T038) y dos que editan `versions.env` (T033 y T035) no son paralelas entre sí.
- Entrega: la PR de `HD` se fusiona antes que la de `INF` (la Application `shoplab-observability` apunta a una ruta que debe existir en `main`).

### Parallel Opportunities

- Setup: T002, T003 y T004.
- US2: T012, T013 y T014 (tres archivos distintos), después T015.
- US4: T020, T021 y T022 (un JSON cada una).
- US3: T028 (`HD`), T031 y T032 (`INF`) en paralelo; T027 y T028 también.
- Polish: T041, T042 y T043.
- Dos personas o sesiones pueden trabajar `HD` (US1 → US2 → US4) e `INF` (T026-T027, T030-T032, T035-T038) a la vez; solo T034 y T040 los reúnen.

---

## Parallel Example: User Story 4

```bash
# Los tres dashboards se escriben a la vez (archivos distintos):
Task: "Crear infra/observability/dashboards/jvm.json"
Task: "Crear infra/observability/dashboards/http.json"
Task: "Crear infra/observability/dashboards/pods.json"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup (T001-T004).
2. Phase 3: US1 (T005-T011).
3. **STOP y validar**: `clean verify` en verde y `curl /actuator/prometheus` con las series del contrato.
4. Ya es desplegable: el backend publica métricas; nada se rompe si nadie las recoge.

### Incremental Delivery

1. US1 → MVP: métricas publicadas.
2. + US2 y US4 → flujo local completo (compose, dashboards, CI) → PR de `HD`.
3. + US3 → clúster (Alertmanager, `ServiceMonitor`, Application) → PR de `INF`.
4. + US5 → alerta probada con `promtool` y en vivo → misma PR de `INF`.
5. Cada incremento se valida con su paso del quickstart antes del siguiente.

### Parallel Team Strategy

1. Una sesión hace Setup y US1.
2. Después: A en `HD` (US2 → US4 → T028-T029), B en `INF` (T026-T027, T030-T032, T035-T038).
3. Se reúnen para T034 y T040 (validación en vivo) y el Polish.

---

## Notes

- `[P]` = archivos distintos y sin dependencias pendientes; `[US#]` enlaza cada tarea con su historia del spec.
- No se crea ningún controlador ni código de producción en el backend: toda la lógica nueva es configuración, por
  lo que los paquetes excluidos de JaCoCo no cambian y la cobertura no debe bajar.
- Lo ya fijado por otras historias no cambia: el `ingress` solo publica `/api` y `/`, `shoplab-dev` y el contrato con
  LAB-81 (`k8s/overlays/dev/kustomization.yaml`) quedan intactos.
- Los paneles de coste de LLM y lag de Kafka no se incluyen (LAB-65).
- Confirmar con `git status` que `.specify/feature.json` y los archivos temporales no entran en los commits.
