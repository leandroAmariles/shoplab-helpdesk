# Validación en vivo: LAB-102

Registro de lo ejecutado al implementar. Rutas y comandos: [quickstart.md](quickstart.md).

## Línea base (T001, 2026-10-26)

- `backend/`: `./mvnw -B -ntp clean verify` sobre `feature/LAB-102-base-metrics-prometheus-grafana` sin cambios de código.
- Resultado: BUILD SUCCESS, 3 pruebas (`ModularityTests` x2, `ShopLabHelpdeskApplicationTests`), Checkstyle y PMD sin
  violaciones, «All coverage checks have been met». El código de producción actual solo tiene la clase `*Application`
  (excluida de JaCoCo): no hay líneas instrumentables, así que el porcentaje no es calculable; la regla pasa vacía.

## US1 · El backend publica sus métricas (T005-T011)

- **T006 (rojo)**: `MetricsEndpointTests` sin dependencia ni configuración: 6 pruebas, 4 fallan (`/actuator/prometheus`
  responde 404: respuesta, histograma, etiqueta y agrupación); `health` y `env` pasan porque son pruebas de guarda.
- **T009 (verde)**: `./mvnw -B -ntp clean verify` → BUILD SUCCESS, 9 pruebas (6 nuevas + `ModularityTests` x2 +
  `ShopLabHelpdeskApplicationTests`), 0 violaciones de Checkstyle, PMD sin violaciones, «All coverage checks have been met».
- **T010 (ejecución real)**: el puerto 8080 de la máquina lo ocupa otra aplicación local (ajena a ShopLab), así que se
  arrancó con `-Dspring-boot.run.arguments=--server.port=18080` y se detuvo al terminar. Resultado:
  - `GET /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`.
  - `GET /actuator/prometheus` → series `jvm_memory_used_bytes{application="helpdesk-backend",area="heap",…}`,
    `jvm_gc_pause_seconds_count{…application="helpdesk-backend"…}` y 207 líneas de
    `http_server_requests_seconds_bucket{application="helpdesk-backend",…,uri="/**",le="…"}` tras una petición a una
    ruta inexistente (las rutas no resueltas se agrupan en `uri="/**"`).
  - `GET /actuator/env` → `404`.
  - El backend arrancó sin Postgres ni Ollama (el contexto no los exige todavía).
- **T011**: `requests.http` generado con 6 peticiones; el escenario 1.5 (ruta con variable) queda en el bloque «Not
  covered here» porque el backend aún no tiene rutas con variable fuera de la prueba.

## US2 · Perfil `observability` en compose (T012-T017)

Entorno: Docker 29.6.1. Imágenes `prom/prometheus:v3.15.0` y `grafana/grafana:13.2.4` (alineadas con el chart 92.13.1).
El puerto 8080 de la máquina lo ocupa un clúster kind de otro proyecto (`ssd-control-plane`), ajeno a ShopLab; para
validar se arrancó el backend en 18080 y un override temporal (fuera del repositorio) apuntó Prometheus a ese puerto.
La configuración versionada sigue apuntando a `host.docker.internal:8080`.

- **2.1 / SC-005**: `docker compose config --services` sin perfil → `ollama postgres` (0 servicios nuevos); con
  `--profile observability` → `prometheus grafana ollama postgres`. `config -q` correcto en ambos casos.
- **2.2**: `up -d --wait prometheus grafana` → ambos `healthy` (healthcheck con `wget --spider` válido en las dos
  imágenes; `grafana` espera a `prometheus`).
- **2.3**: `/api/v1/targets` → `helpdesk-backend` en `up` (`http://host.docker.internal:18080/actuator/prometheus`);
  `up{job="helpdesk-backend"} = 1`.
- **2.4**: Grafana 13.2.4 `api/health` → `database: ok`; `/api/datasources` → `Prometheus`, tipo `prometheus`,
  `http://prometheus:9090`, por defecto, sin pasos manuales.
- **2.5**: backend detenido → a los 35 s `up{job="helpdesk-backend"} = 0`; Prometheus y Grafana siguen `healthy`.
- Ollama y Postgres no se arrancaron en esta validación (no hacen falta para el perfil).

## US4 · Dashboards versionados como código (T018-T025)

- **T019 (rojo)**: `infra/observability/check-dashboards.sh` falla en los 5 casos negativos (directorio sin JSON, JSON
  inválido, `uid` y `title` repetidos, sin variable `datasource`, panel con `uid` de datasource fijo). Se usa Python en
  vez de `jq` (no instalado en esta máquina y presente en `ubuntu-latest`), con el mismo contrato.
- **T023 (verde)**: el script valida los 3 dashboards (`jvm.json`, `http.json`, `pods.json`).
- **T025 · 4.1/4.5**: se borraron solo los contenedores y volúmenes de observabilidad (`rm -sfv` + `docker volume rm`;
  `shoplab_pgdata` y `shoplab_ollama_models` no se tocaron) y se levantó de nuevo: Grafana 13.2.4 lista
  `shoplab-jvm`, `shoplab-http` y `shoplab-pods` en la carpeta «ShopLab» sin importar nada.
- **4.2 / 4.3 / SC-001**: con el backend real en 18080 y tráfico (`/actuator/health` y una ruta inexistente), cada
  consulta de los paneles de JVM y HTTP devuelve series en Prometheus (heap 1+1, hilos 1+1, GC 1, CPU 1+1, tasa por
  ruta 3, p95 3, estados 2); los paneles de errores 5xx muestran 0 (`or vector(0)`) porque no hubo errores.
- **4.4**: el dashboard de pods devuelve 0 series en compose (no hay pods): «sin datos», como estaba previsto.
- **4.5**: guardar un dashboard aprovisionado por la API de Grafana se rechaza (`Cannot save provisioned dashboard`,
  `allowUiUpdates: false`); tras `restart grafana` el dashboard sigue aprovisionado desde git (`provisioned = True`).
- **T024**: job `observability` añadido a `ci.yml` (filtro `infra/observability/**` e `infra/docker-compose.yml`, y
  `ci-gate`). El YAML carga y `ci-gate` lo incluye en `needs`; su ejecución real queda para la PR.

## US3 · Clúster: ServiceMonitor, Alertmanager y dashboards (T026-T033; T034 pendiente)

Herramientas locales: `terraform`, `tflint`, `kustomize` (5.8.1, `versions.env` pide 5.8.2) ya instaladas; `yq` 4.53.6,
`kubeconform` 0.8.0 y `promtool` 3.15.0 descargados de sus releases oficiales, con suma SHA-256 verificada contra la
publicada, a una carpeta temporal fuera del repositorio. No hay `make` en esta máquina: se ejecutaron los scripts
que invoca cada objetivo. Trivy no está instalado: el paso `trivy` de `make check` queda para CI.

- **T027 (rojo)**: `terraform test` del módulo `platform` con las 2 pruebas nuevas: fallan (Alertmanager con
  `enabled = false`, sin selectores abiertos).
- **T030 (verde)**: `monitoring.tf` activa Alertmanager (10m/32Mi, sin almacenamiento) y pone
  `serviceMonitorSelectorNilUsesHelmValues`/`ruleSelectorNilUsesHelmValues` en `false`: 15 pruebas pasan,
  `terraform validate` correcto.
- **T033**: `terraform fmt -check`, `check-layout`/`check-pins` (todo fijado), `terraform validate` (módulos, `dev` y
  `bootstrap`), `terraform test` de los 4 módulos, `tflint`, `kustomize build` + `kubeconform` (overlay `dev`: 26 recursos
  válidos con `ServiceMonitor` y `PrometheusRule`, esquemas del catálogo de CRDs confirmados; `k8s/argocd`: 2 recursos),
  políticas (`policy-check` ok en `dev`, `argocd` y `argocd-root`, incluida la Application nueva con repo, revisión y
  finalizer correctos) y las pruebas de las políticas: todo en verde.
- **HD**: `kubectl kustomize infra/observability` rinde 3 `ConfigMap` en `monitoring` con `grafana_dashboard: "1"` (la
  misma comprobación que añade el job `observability` de CI).
- **T034 pendiente**: la validación en el clúster (`make up`, objetivo `backend` en `up`, `shoplab-dev` y
  `shoplab-observability` en Synced, 3 dashboards con datos, reconstrucción con `make down && make up`) exige que la PR
  de `shoplab-helpdesk` esté fusionada en `main` (la Application lee esa ruta) y que el backend nuevo esté desplegado
  por el despliegue continuo de LAB-81. No se puede hacer antes de fusionar.

## US5 · Alerta `BackendDown` (T035-T039; T040 pendiente)

- **T036/T037 (rojo)**: `scripts/prom-rules.sh` sin la regla falla con «el overlay dev no contiene ninguna PrometheusRule».
- **T038/T039 (verde)**: con `prometheusrule.yaml`, `promtool check rules` → 1 regla correcta y `promtool test rules` → los
  5 casos (`up == 0` sostenido, serie ausente, corte breve, backend sano, caído en otro namespace) pasan.
- **Comprobación de que la prueba muerde**: en una copia temporal, quitar `absent(...)` hace fallar el caso «sin ninguna
  serie up (0 réplicas)», y subir `for` a `10m` hace fallar los casos que esperan la alerta.
- **Corrección del diseño**: al escribir los casos se vio que un backend caído en *otro* namespace sin series en
  `shoplab-dev` sí debe disparar (`absent()`); el caso correcto es «otro namespace caído y `shoplab-dev` sano», y el
  contrato se corrigió.
- **T040 pendiente** (misma razón que T034): la alerta en vivo con 0 réplicas y su resolución se validan en el clúster
  tras fusionar.

## Revisión previa al commit (T045)

- `HD`: `./mvnw -B -ntp clean verify` → BUILD SUCCESS, 9 pruebas, 0 violaciones de Checkstyle, PMD sin violaciones y
  «All coverage checks have been met» (sin cambio de código de producción, cobertura intacta). `git status --short` sin
  archivos sueltos (`target/` está ignorado); `git diff --check` sin errores. Los finales de línea del árbol de trabajo
  son CRLF (`core.autocrlf`) y el repositorio normaliza a LF (`* text=auto`, `*.sh eol=lf`).
- `INF`: los controles de `make check` ejecutables aquí pasan (fmt, estructura y pines, `terraform validate`,
  `terraform test` de los 4 módulos, `tflint`, `kustomize` + `kubeconform`, políticas, pruebas de las políticas y
  `prom-rules`); queda `trivy` para el CI. `git diff --check` sin errores.
- Recursos de la validación local eliminados: contenedores `prometheus` y `grafana`, sus dos volúmenes y los procesos del
  backend en 18080; `shoplab_pgdata` y `shoplab_ollama_models` intactos.

## Validación en el clúster local (T034, T040 y paso E), 2026-10-26

Con las dos PR fusionadas: `shoplab-helpdesk` #61 (`77aebd1`) y `shoplab-infra` #13 (`272586e`), y el backend con
`/actuator/prometheus` desplegado por LAB-81 (imagen `ghcr.io/leandroamariles/backend:77aebd1…`). `shoplab-infra` en `main`;
`scripts/up.sh` y `scripts/down.sh` (no hay `make` en esta máquina); Floci y k3s en Docker (24 CPUs, 16 GB).

- **Arranque desde cero**: `up.sh` en 4 min 31 s (14:46:54 → 14:51:25). `shoplab-root` y `shoplab-observability` en
  `Synced/Healthy`; `shoplab-dev` en `Synced/Degraded` por Postgres y Ollama en `Pending` (el k3s de Floci no trae
  `StorageClass`, limitación ya documentada en `docs/kustomize.md` y ajena a esta historia). Alertmanager `2/2 Running`.
- **3.1 / 3.4 / SC-002**: `/api/v1/targets` → un objetivo `backend` en `up` (`http://10.42.0.25:8080/actuator/prometheus`,
  namespace `shoplab-dev`); `jvm_memory_used_bytes{application="helpdesk-backend",job="backend",…}` con 3 series. El
  objetivo no aparece hasta ~2-3 minutos después de crearse el `ServiceMonitor` (la primera consulta, a los ~77 s de su
  creación, no devolvió nada; las siguientes sí): es el tiempo que tarda el operador en recargar la configuración.
- **3.2 / SC-002**: Grafana lista `shoplab-jvm`, `shoplab-http` y `shoplab-pods` cargados por el sidecar desde los
  `ConfigMap` de `shoplab-observability`. En el clúster quedan en la carpeta *General*, no en «ShopLab» (la carpeta solo
  la crea el aprovisionamiento por archivos de compose). Las consultas de los paneles contra el Prometheus del clúster:
  - JVM y HTTP: todos los paneles devuelven series.
  - Pods: estado, pods listos y reinicios devuelven series; **CPU y memoria del contenedor devuelven 0 series**. Causa:
    el kubelet del k3s de Floci rechaza el scrape con `401 Unauthorized` (`https://172.17.0.3:10250/metrics`,
    `/metrics/cadvisor` y `/metrics/probes` en `down`), así que no hay ninguna métrica de cAdvisor
    (`container_cpu_usage_seconds_total`, `container_memory_working_set_bytes` y `kubelet_running_pods` con 0 series).
    Es una limitación del entorno local de LAB-98, no del dashboard; el spec (FR-015) exige estado, reinicios y
    disponibilidad, que sí funcionan.
- **5.1**: la regla `BackendDown` aparece cargada (`state=inactive`, `for=120`, `health=ok`). Justo tras crearse el
  `ServiceMonitor` quedó `pending` unos minutos (activa desde las 19:52:43 UTC, antes de que existiera el objetivo, porque
  `absent()` era verdadero) y se limpió sola sin llegar a dispararse.
- **5.2 / SC-004 (alerta)**: `kubectl scale deployment/backend --replicas=0` a las 14:54:20 → la serie `up` desaparece a los
  ~46 s y `BackendDown` está `active` / `critical` en Alertmanager a los **185 s** (< 5 min). El HPA no lo restaura desde 0.
- **5.4 (resolución)**: `--replicas=1` a las 14:57:41 → la alerta desaparece de Alertmanager a los **77 s** (< 5 min).
- **5.3 (reinicio normal)**: `kubectl rollout restart deployment/backend` y 16 comprobaciones durante 250 s: la regla
  permaneció `inactive` y Alertmanager sin `BackendDown` en todas.
- **Paso E / 3.3 / 4.5 / 5.5 / SC-003**: `down.sh` (2 min 30 s, `verify-clean.sh` → «entorno limpio») y `up.sh` de nuevo
  (4 min 5 s). Sin ningún paso manual, a los pocos minutos: `shoplab-observability` `Synced/Healthy`, objetivo `backend` en
  `up`, la regla `BackendDown` cargada e `inactive`, los 3 dashboards en Grafana y solo `Watchdog` en Alertmanager.
- **Limpieza**: port-forwards cerrados y clúster destruido (`down.sh` → «entorno limpio»); solo quedan contenedores
  parados que ya existían y no son de esta validación.

## Resumen

Todas las tareas (T001-T046) están completadas. Desviaciones respecto al diseño: dashboards en *General* en el clúster y
sin CPU/memoria del contenedor en el clúster local de Floci (kubelet `401`), ambas documentadas en `docs/observabilidad.md`.
