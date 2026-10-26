# Data Model: Métricas base con Prometheus y Grafana

No hay datos de aplicación nuevos ni migraciones. Las entidades son recursos de configuración y series temporales;
se describen aquí con sus campos, relaciones y reglas de validación.

## Entidades

### Métrica (serie temporal)

| Campo | Descripción | Validación |
|---|---|---|
| `name` | Nombre de la serie (`http_server_requests_seconds_bucket`, `jvm_memory_used_bytes`, …) | Formato de exposición de Prometheus |
| `application` | Etiqueta común: `helpdesk-backend` | Presente en el 100 % de las series del backend (FR-004) |
| `uri` | Plantilla de la ruta HTTP (`/tickets/{id}`) | Nunca un valor concreto de identificador (FR-005) |
| `method`, `status`, `outcome` | Etiquetas estándar de `http.server.requests` | Cardinalidad acotada por el contrato HTTP |
| `le` | Límite del cubo del histograma | Necesario para p95 (FR-003) |

### Objetivo de recogida (target)

| Campo | Compose | Clúster |
|---|---|---|
| `job` | `helpdesk-backend` | `backend` (nombre del `Service`) |
| `instance` | `host.docker.internal:8080` | `<pod-ip>:8080` (uno por réplica) |
| `namespace` | n/a | `shoplab-dev` |
| Estado | `up` = 1 sano, 0 caído | igual; la serie `up` desaparece si no hay pods |

Relación: un objetivo produce muchas métricas. En el clúster lo declara el `ServiceMonitor`; en compose, el
`scrape_config`.

### ServiceMonitor (clúster)

| Campo | Valor |
|---|---|
| `metadata.name` | `backend` |
| `spec.selector.matchLabels` | `app.kubernetes.io/name: backend` (el del `Service`) |
| `spec.endpoints[0]` | `port: http`, `path: /actuator/prometheus`, `interval: 30s` |

Validación: debe construirse con `kustomize build` y pasar `kubeconform` y las políticas (sin `Secret` en claro, sin
`latest`).

### Dashboard

| Campo | Valor / regla |
|---|---|
| `uid` | `shoplab-jvm`, `shoplab-http`, `shoplab-pods`: estables y únicos |
| `title` | Estable y no vacío |
| Variable `datasource` | Tipo `datasource`, consulta `prometheus`: sin `uid` fijo del origen de datos |
| Variable de filtro | `application` (JVM, HTTP) o `namespace`/`pod` (pods) |
| Etiqueta del `ConfigMap` | `grafana_dashboard: "1"` (la lee el sidecar) |
| Origen | Archivo JSON en git; nunca editado en la interfaz (FR-014) |

Validación en CI: JSON válido (`jq empty`), `uid` y `title` presentes y sin duplicados entre archivos.

### Regla de alerta (`PrometheusRule` → grupo `backend`)

| Campo | Valor |
|---|---|
| `alert` | `BackendDown` |
| `expr` | `up{job="backend",namespace="shoplab-dev"} == 0 or absent(up{job="backend",namespace="shoplab-dev"})` |
| `for` | `2m` |
| `labels.severity` | `critical` |
| `annotations` | `summary`, `description` (qué falló y dónde mirar) |

### Alerta

Instancia de `BackendDown`. Estados: **inactiva → pendiente** (condición verdadera, dentro de `for`) → **disparada**
(llega a Alertmanager) → **resuelta** (la condición deja de cumplirse, sin intervención).

```text
sano ──(up==0 o sin serie)──▶ pendiente ──(2 min)──▶ disparada ──(backend responde)──▶ resuelta
                                  └──(vuelve antes de 2 min)──▶ sano
```

## Relaciones

```text
Backend ──publica──▶ Métrica ◀──recoge── Objetivo ◀──declara── ServiceMonitor (clúster) / scrape_config (compose)
Métrica ──alimenta──▶ Dashboard
Métrica (up) ──evalúa──▶ Regla ──dispara──▶ Alerta ──llega a──▶ Alertmanager
```
