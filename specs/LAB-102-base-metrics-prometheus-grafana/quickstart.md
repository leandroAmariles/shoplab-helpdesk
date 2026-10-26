# Quickstart: validar LAB-102 de punta a punta

Guía de validación; el detalle del diseño está en [contracts/](contracts/) y [data-model.md](data-model.md). El
resultado de ejecutarla se registra en `validation.md` al implementar (como en LAB-101).

## Requisitos

- Docker con compose, Java 21 (`./mvnw` en `backend/`), `curl` y `jq`.
- Para el clúster: `shoplab-infra` clonado junto a este repositorio y las herramientas de su `make check-tools`.
- Las imágenes de Prometheus y Grafana se descargan al primer arranque; Ollama no hace falta para esta validación.

## A. El backend publica métricas (US1)

```bash
cd backend
./mvnw -B -ntp clean verify                      # pruebas de MetricsEndpointTests, Checkstyle, PMD, JaCoCo >= 80 %
./mvnw spring-boot:run &                         # necesita Postgres: docker compose -f ../infra/docker-compose.yml up -d postgres --wait
curl -s localhost:8080/actuator/prometheus | head -20
curl -s localhost:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/actuator/env   # esperado: 404
```

Esperado: texto de Prometheus con `jvm_memory_used_bytes{application="helpdesk-backend",…}` y
`http_server_requests_seconds_bucket`; `health` en `UP`; `env` en `404`.

## B. Perfil `observability` en compose (US2 y US4)

```bash
docker compose -f infra/docker-compose.yml up -d --wait                         # sin perfil: 0 contenedores nuevos
docker compose -f infra/docker-compose.yml --profile observability up -d --wait
curl -s localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {job:.labels.job, health}'
curl -s localhost:3000/api/health
curl -s -u admin:change-me-local-only localhost:3000/api/search | jq '.[].uid'
```

Esperado: el objetivo `helpdesk-backend` en `up`; Grafana responde `ok`; aparecen `shoplab-jvm`, `shoplab-http` y
`shoplab-pods` sin importar nada a mano. Abre `http://localhost:3000`, genera tráfico (`curl localhost:8080/actuator/health`
en bucle) y comprueba que los paneles de JVM y HTTP muestran datos en menos de 2 minutos (SC-001).

Backend detenido: `up` pasa a `0` en Prometheus y los paneles lo reflejan sin que Grafana falle (escenario 2.5).
Reproducibilidad: borra solo los servicios y volúmenes de observabilidad (`docker compose -f infra/docker-compose.yml
rm -sfv prometheus grafana` y `docker volume rm shoplab_prometheus_data shoplab_grafana_data`) y vuelve a levantar; los
dashboards reaparecen (2.6, SC-003). **No uses `down -v` con el perfil**: también borraría `pgdata` y `ollama_models`. El dashboard de pods queda «sin datos» en compose, es lo esperado.

## C. Clúster local (US3 y US5) — tras fusionar la PR de helpdesk

```bash
cd ../shoplab-infra
make check                  # incluye kustomize, kubeconform, políticas y promtool
make up                     # clúster desde cero; backend desplegado por Argo CD
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80 &
kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093:9093 &
curl -s localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="backend") | .health'
kubectl -n argocd get applications          # shoplab-root, shoplab-dev y shoplab-observability
```

Esperado: el backend como objetivo `up` (uno por réplica); `shoplab-dev` en Synced/Healthy; los 3 dashboards en
Grafana (carpeta *General*; en compose están en «ShopLab») con datos; en Prometheus, la regla `BackendDown` cargada y en estado `inactive`.
Los nombres exactos de los `Service` dependen del release (`kube-prometheus-stack`); se ajustan si difieren.

## D. Alerta «backend caído» (US5)

```bash
kubectl -n shoplab-dev scale deployment/backend --replicas=0
# esperar ~3 min
curl -s localhost:9093/api/v2/alerts | jq '.[] | {name:.labels.alertname, severity:.labels.severity, state:.status.state}'
kubectl -n shoplab-dev scale deployment/backend --replicas=1     # (Argo CD con selfHeal también lo restaura)
# esperar la recuperación y repetir la consulta: la alerta desaparece
```

Esperado: `BackendDown`, `critical`, `active` en menos de 5 minutos con 0 réplicas; resuelta sola al volver a
responder. Un `kubectl rollout restart` no debe dispararla (escenario 5.3).

## E. Reconstrucción (SC-003)

`make down && make up` en `shoplab-infra`: los dashboards, el `ServiceMonitor` y la regla reaparecen sin pasos
manuales (3.3, 4.5, 5.5).

## Mapeo escenario → paso

| Historia | Escenarios | Paso |
|---|---|---|
| US1 | 1.1-1.5 | A (y `MetricsEndpointTests`) |
| US2 | 2.1-2.6 | B |
| US3 | 3.1-3.5 | C y E |
| US4 | 4.1-4.6 | B (JSON, aprovisionamiento), C y CI del job `observability` |
| US5 | 5.1-5.5 | C, D, `promtool test rules` y E |
