# Observabilidad base: métricas, dashboards y alerta

Cómo ver el comportamiento del backend y del clúster desde el principio (LAB-102). La decisión de dónde viven los
dashboards y por qué está en el [ADR-0009](adr/0009-observabilidad-base-fuente-unica-de-dashboards.md). Los paneles
de coste de LLM y de lag de Kafka llegan con LAB-65.

## Qué hay

| Pieza | Dónde | Qué hace |
| --- | --- | --- |
| Punto de métricas | `GET /actuator/prometheus` del backend | Publica métricas de JVM y HTTP en formato Prometheus, todas con `application="helpdesk-backend"` y la latencia como histograma |
| Prometheus + Grafana locales | Perfil `observability` de `infra/docker-compose.yml` | Recogen el backend que corre en tu máquina y muestran los dashboards |
| Dashboards | `infra/observability/dashboards/*.json` | `ShopLab · JVM`, `ShopLab · HTTP` y `ShopLab · Pods`; una sola copia para compose y clúster |
| Clúster | `shoplab-infra` | `ServiceMonitor` del backend, regla `BackendDown`, Alertmanager y los mismos dashboards vía Argo CD |

`/actuator/prometheus` no sale por el ingress (solo `/api` y `/`): se recoge por la red interna de compose o del clúster.
Del resto de endpoints de Actuator solo está expuesto `health`.

## En local (docker compose)

```bash
docker compose -f infra/docker-compose.yml --profile observability up -d --wait   # Prometheus y Grafana
cd backend && ./mvnw spring-boot:run                                               # el backend, en el puerto 8080
```

| Servicio | URL | Acceso |
| --- | --- | --- |
| Prometheus | `http://localhost:9090` (`PROMETHEUS_PORT`) | sin autenticación |
| Grafana | `http://localhost:3000` (`GRAFANA_PORT`) | usuario `admin`, contraseña `GRAFANA_ADMIN_PASSWORD` (por defecto `change-me-local-only`, solo válida en local) |

- En Prometheus, *Status → Targets* debe mostrar `helpdesk-backend` en `up`. Los dashboards están en la carpeta *ShopLab*
  de Grafana, con el origen de datos ya creado.
- Prometheus recoge `host.docker.internal:8080`: el backend corre en el host y el puerto es fijo (Prometheus no expande
  variables de entorno). Si arrancas el backend en otro puerto, edita el objetivo de
  `infra/observability/prometheus/prometheus.yml`.
- El dashboard `ShopLab · Pods` muestra «sin datos» en compose: no hay pods.
- Sin `--profile observability` no arranca nada de esto.
- Para borrar solo los datos de observabilidad: `docker compose -f infra/docker-compose.yml rm -sfv prometheus grafana` y
  `docker volume rm shoplab_prometheus_data shoplab_grafana_data`. **No uses `down -v`**: borraría también `pgdata` y
  `ollama_models`.

## En el clúster local

Lo despliega `shoplab-infra` (ver su `docs/observability.md`): el backend aparece como objetivo `backend` en Prometheus, la
alerta `BackendDown` en Alertmanager y los tres dashboards en Grafana, sin pasos manuales tras `make up`.

## Qué muestra cada dashboard

| Dashboard (`uid`) | Paneles |
| --- | --- |
| `ShopLab · JVM` (`shoplab-jvm`) | Heap usada y máxima, hilos vivos y daemon, pausas de GC (tiempo por segundo y máximo), CPU del proceso y del sistema, tiempo en marcha |
| `ShopLab · HTTP` (`shoplab-http`) | Tasa de peticiones por ruta, latencia p95 por ruta, tasa y porcentaje de errores 5xx, peticiones por estado |
| `ShopLab · Pods` (`shoplab-pods`) | Estado y disponibilidad de los pods del backend, reinicios, CPU y memoria del contenedor (solo en el clúster) |

Las rutas se agrupan por su plantilla (`/tickets/{id}`), no por el valor concreto, para no multiplicar las series. Las
rutas que no existen se agrupan en `/**`.

## Cómo añadir o cambiar un dashboard

1. Edita o añade el JSON en `infra/observability/dashboards/`. Debe tener `uid` y `title` únicos y estables, la variable
   `datasource` (tipo datasource, consulta `prometheus`) y ningún `uid` de datasource fijo en los paneles.
2. Si es nuevo, súmalo al `configMapGenerator` de `infra/observability/kustomization.yaml` (un `ConfigMap` por dashboard).
3. Valida en local: `bash infra/observability/check-dashboards.sh` y `kubectl kustomize infra/observability`.
4. Abre una PR: el job `observability` de CI repite esas comprobaciones y valida el perfil de compose.

Los dashboards no se editan desde la interfaz de Grafana: están aprovisionados desde git (`allowUiUpdates: false`) y un
cambio hecho allí se rechaza o se pierde al reiniciar. En el clúster, al fusionar en `main`, Argo CD lo aplica solo.

## Cómo probar la alerta

En el clúster local, con Alertmanager accesible (`kubectl -n monitoring port-forward svc/kube-prometheus-stack-alertmanager 9093:9093`):

```bash
kubectl -n shoplab-dev scale deployment/backend --replicas=0     # el HPA o Argo CD (selfHeal) lo restauran después
# a los ~3 minutos
curl -s localhost:9093/api/v2/alerts | jq '.[] | {alertname: .labels.alertname, severity: .labels.severity}'
kubectl -n shoplab-dev scale deployment/backend --replicas=1     # la alerta se resuelve sola al volver a responder
```

`BackendDown` salta si el backend no responde (`up == 0`) o si desaparece del todo (0 réplicas) durante más de 2 minutos;
un despliegue progresivo no la dispara. No hay receptores externos: la alerta se ve en la interfaz y en la API de Alertmanager.
