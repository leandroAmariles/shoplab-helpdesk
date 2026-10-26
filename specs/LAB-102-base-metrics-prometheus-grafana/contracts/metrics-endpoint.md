# Contrato: punto de métricas del backend

## Endpoint

| Aspecto | Contrato |
|---|---|
| Ruta | `GET /actuator/prometheus` |
| Puerto | El de la aplicación (`8080`); no hay puerto de gestión separado |
| Formato | Texto de exposición de Prometheus (`text/plain; version=0.0.4` o el que negocie el cliente) |
| Autenticación | Ninguna; acceso solo desde la red interna de compose o del clúster |
| Publicación | NO sale por el ingress (el ingress solo publica `/api` y `/`) |
| Endpoints de Actuator expuestos | Exactamente `health` y `prometheus`; el resto, no (FR-006) |
| `GET /actuator/health` | Sin cambios respecto a antes de la historia, incluidos los grupos `liveness` y `readiness` que usan las sondas |

## Configuración que lo produce

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

Más la dependencia `io.micrometer:micrometer-registry-prometheus` (versión del BOM de Boot).

## Series garantizadas

| Familia | Ejemplos | Uso |
|---|---|---|
| JVM memoria | `jvm_memory_used_bytes`, `jvm_memory_max_bytes` (`area`, `id`) | Heap en el dashboard JVM |
| JVM hilos | `jvm_threads_live_threads`, `jvm_threads_states_threads` | Hilos |
| JVM GC | `jvm_gc_pause_seconds_count`, `_sum`, `_max` | Pausas |
| HTTP | `http_server_requests_seconds_count`, `_sum`, `_bucket` (`method`, `uri`, `status`, `outcome`) | Tasa, errores, p95 |
| Proceso | `process_cpu_usage`, `system_cpu_usage`, `process_uptime_seconds` | Contexto |

## Reglas

1. Toda serie lleva `application="helpdesk-backend"`.
2. `uri` es siempre la plantilla de la ruta; las rutas no resueltas se agrupan en un único valor (`/**`, verificado en ejecución real).
3. Ninguna etiqueta puede tomar valores ilimitados (identificadores, textos de consulta, correos).
4. La latencia p95 se calcula con `histogram_quantile(0.95, sum by (le, uri) (rate(
   http_server_requests_seconds_bucket{application="helpdesk-backend"}[5m])))`.

## Pruebas del contrato (`MetricsEndpointTests`)

| Prueba | Verifica |
|---|---|
| Responde con métricas JVM y HTTP | `200`, contiene `jvm_memory_used_bytes` y `http_server_requests_seconds_count` (escenario 1.1) |
| Publica cubos de histograma | Tras una petición, aparece `http_server_requests_seconds_bucket` (1.2) |
| Etiqueta la aplicación | Las series llevan `application="helpdesk-backend"` (1.3) |
| Mantiene `health` | `GET /actuator/health` responde `200` con `status: UP` (1.4) |
| Agrupa por plantilla | Dos peticiones con identificadores distintos a una ruta con variable producen **una** serie con `uri="/…/{id}"` (1.5) |
| No expone otros endpoints | `GET /actuator/env` responde `404` (FR-006) |

La prueba hace llamadas HTTP reales (`@SpringBootTest` con puerto aleatorio; `MockMvc` no está en el classpath del
proyecto) y usa un controlador solo de pruebas con una ruta con variable para no acoplarse al código de negocio.
