# Contrato: disposición de archivos, compose y recursos del clúster

## 1. Perfil `observability` de compose (`infra/docker-compose.yml`)

| Servicio | Imagen | Puerto host | Volúmenes | Salud |
|---|---|---|---|---|
| `prometheus` | `${PROMETHEUS_IMAGE:-prom/prometheus:<tag fijo>}` | `${PROMETHEUS_PORT:-9090}:9090` | `./observability/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro`, `prometheus_data` | `wget -q --spider http://localhost:9090/-/healthy` |
| `grafana` | `${GRAFANA_IMAGE:-grafana/grafana:<tag fijo>}` | `${GRAFANA_PORT:-3000}:3000` | `./observability/grafana/provisioning:/etc/grafana/provisioning:ro`, `./observability/dashboards:/var/lib/grafana/dashboards:ro`, `grafana_data` | `wget -q --spider http://localhost:3000/api/health` |

- Ambos llevan `profiles: [observability]`, `restart: unless-stopped` y `healthcheck` con `start_period`, como el resto.
- `prometheus` añade `extra_hosts: ["host.docker.internal:host-gateway"]`.
- `grafana` depende de `prometheus` con `condition: service_healthy`.
- Variables nuevas: `PROMETHEUS_IMAGE`, `GRAFANA_IMAGE`, `PROMETHEUS_PORT`, `GRAFANA_PORT`, `GRAFANA_ADMIN_PASSWORD`
  (por defecto `change-me-local-only`, solo válido en local).
- Sin el perfil, `docker compose up` no arranca ninguno de los dos (0 contenedores nuevos, SC-005).
- El comentario de cabecera del archivo y la tabla de perfiles del `README.md` dejan de decir «reservado».

## 2. Archivos de `infra/observability/` (`shoplab-helpdesk`)

| Archivo | Contenido |
|---|---|
| `prometheus/prometheus.yml` | `scrape_interval: 15s`; job `helpdesk-backend`, `metrics_path: /actuator/prometheus`, objetivo `host.docker.internal:8080` |
| `grafana/provisioning/datasources/prometheus.yml` | Datasource `Prometheus` por defecto, `url: http://prometheus:9090` |
| `grafana/provisioning/dashboards/dashboards.yml` | Proveedor «ShopLab» que lee `/var/lib/grafana/dashboards`, `allowUiUpdates: false` |
| `dashboards/jvm.json` | `uid: shoplab-jvm` |
| `dashboards/http.json` | `uid: shoplab-http` |
| `dashboards/pods.json` | `uid: shoplab-pods` |
| `kustomization.yaml` | `configMapGenerator` por dashboard: `namespace: monitoring`, etiqueta `grafana_dashboard: "1"`, `generatorOptions.disableNameSuffixHash: true` |

El `kustomization.yaml` está en la raíz de `infra/observability/` para leer `dashboards/*.json` sin salir de su
directorio (Argo CD no activa `--load-restrictor LoadRestrictionsNone`).

## 3. Paneles mínimos por dashboard

| Dashboard | Paneles (FR-015) |
|---|---|
| `shoplab-jvm` | Memoria heap usada/máxima; hilos vivos; pausas de recolección de basura (tasa y máximo); uso de CPU del proceso |
| `shoplab-http` | Tasa de peticiones por ruta; latencia p95 por ruta; tasa de errores 5xx; peticiones por estado |
| `shoplab-pods` | Estado de los pods del backend (`kube_pod_status_phase`); reinicios (`kube_pod_container_status_restarts_total`); pods listos (`kube_pod_status_ready`); CPU y memoria del contenedor |

Todos usan la variable `datasource` (tipo datasource) y, en JVM y HTTP, la variable `application` con valor por
defecto `helpdesk-backend`.

## 4. Recursos del clúster (`shoplab-infra`)

| Recurso | Ubicación | Cambio |
|---|---|---|
| Alertmanager | `terraform/modules/platform/monitoring.tf` | `alertmanager.enabled = true`, requests `10m`/`32Mi`, sin PVC |
| Selectores | mismo archivo, `prometheus.prometheusSpec` | `serviceMonitorSelectorNilUsesHelmValues = false`, `ruleSelectorNilUsesHelmValues = false` |
| `ServiceMonitor/backend` | `k8s/base/backend/servicemonitor.yaml` | Selecciona `app.kubernetes.io/name: backend`, puerto `http`, ruta `/actuator/prometheus`, 30 s |
| `PrometheusRule/backend` | `k8s/base/backend/prometheusrule.yaml` | Grupo `backend` con `BackendDown` (ver [backend-down-alert.md](backend-down-alert.md)) |
| `Application/shoplab-observability` | `k8s/argocd/application-observability.yaml` | `repoURL` de `shoplab-helpdesk`, `path: infra/observability`, `targetRevision: main`, destino `monitoring`, `prune`+`selfHeal`, finalizer |
| Lista de Applications | `k8s/argocd/kustomization.yaml` | + `application-observability.yaml` |
| Humo en CI | `versions.env` | `ARGOCD_PRESENT_APPS=shoplab-dev,shoplab-observability` (hoy `shoplab-dev`; formato por confirmar en `smoke.sh`) |

## 5. CI

| Dónde | Job / paso | Falla si |
|---|---|---|
| `shoplab-helpdesk/.github/workflows/ci.yml` | Job `observability` (filtro `infra/observability/**`), añadido a `needs` de `ci-gate` | un JSON de dashboard es inválido, `uid`/`title` faltan o se repiten, `kustomize build infra/observability` falla o `docker compose --profile observability config` falla |
| `shoplab-infra` `make check` | `kustomize`, `kubeconform`, políticas (ya existentes) | el `ServiceMonitor` o la regla no construyen o no tienen esquema |
| `shoplab-infra` `make check` | Nuevo `promtool` | `promtool check rules` o `promtool test rules` fallan |
