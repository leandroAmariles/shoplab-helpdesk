# Validación: LAB-130

Evidencia por paso de [quickstart.md](quickstart.md). Estado al 2026-11-26.

## Estado de la implementación

| Fase | Estado | Evidencia |
|---|---|---|
| A. Servicio en paralelo (US1, US2) | **hecho** | `shoplab-eval-service` PR #5 y release PR #6 fusionados; `v0.1.0` publicada: imagen `ghcr.io/leandroamariles/eval-service:v0.1.0` firmada con Cosign y verificada, despliegue GitOps ejecutado, stubs `com.shoplab:shoplab-eval-service:0.1.0:stubs` publicados. CI: 430 pruebas, JaCoCo ≥ 80 %, Checkstyle, PMD, Qodana y Trivy en verde |
| B. Datos y despliegue (US3, parcial) | **parcial** | Scripts de migración/verificación probados y corte ensayado en aislado (abajo). Manifiestos `apps/eval-service` fusionados en `shoplab-infra` (PR #48, CI en verde). **Pendiente**: secretos de dev, base `eval` en el PostgreSQL de dev, migración y comparación con línea base |
| C. Corte | pendiente | Requiere ventana en dev y aprobación (T044–T050) |
| US5. Documentación | hecho | ADR-0035, mapa de servicios, módulos, contratos, `admin-eval`, procedimiento de retiro del esquema |
| D. Retiro de `backend/` (US4) | pendiente | Solo tras verificar el corte (T057–T067) |

## T037 — Scripts de migración contra PostgreSQL 16 desechable

Contenedor `postgres:16`, esquema `eval` con las migraciones V1/V2 reales y datos de ejemplo (2 ejecuciones, 1 caso, 1 resultado).

| Comprobación | Resultado |
|---|---|
| `eval-db-init.sh` dos veces (idempotente) | `base 'eval' lista` ×2 |
| `migrate-eval` primera vez | exit 0 |
| `migrate-eval` segunda vez sin `--replace` | rechaza con exit 3 («el destino ya tiene 4 filas») |
| `migrate-eval --replace` | exit 0 |
| `verify-eval-migration` tras migrar | exit 0: recuentos y MD5 de `eval_run`, `eval_case`, `eval_result`, `flyway_schema_history` **IGUAL**; secuencias `last_value >= max_id` |
| Nueva ejecución en destino | recibe `id=3` > máximo migrado (2): sin colisión |
| `verify` tras alterar el destino | exit 1: `DISTINTO eval_run` (detecta la diferencia) |
| Secuencia atrasada | exit 1: `DESALINEADA (last_value=null, max_id=2)` (rechaza) |

## Ensayo del corte en un entorno aislado (2026-11-26)

PostgreSQL `pgvector:0.8.7-pg16` desechable, monolito publicado `backend:v0.31.0`, `eval-service:v0.1.0` publicado
(usuario 10001, sistema de archivos de solo lectura, sin capabilities, como en el Deployment) y un stub de
`GET /api/assistant/settings` con el cuerpo del contrato de `chat`. No tocó los datos locales. No usó `chat-service`
real, gateway, herramienta Python ni `admin-eval`.

| Paso del quickstart | Resultado |
|---|---|
| 1. `requests.http` contra el monolito | 16/16 (tras corregir los casos #6 y #11 del archivo) |
| 1. Línea base grabada en el monolito y comparada consigo mismo | 0 diferencias (5 respuestas) |
| 3. Guardado suspendido (token vacío) | `POST` → `403 SLHD_EVAL_00001`; el historial responde `200` |
| 1. `eval-db-init` dos veces, `migrate-eval`, `verify-eval-migration` | exit 0: 3 ejecuciones, 2 casos, 5 resultados, MD5 iguales y secuencias alineadas |
| 1. Paridad del historial servicio vs. línea base (bash y PowerShell) | 0 diferencias; una línea base alterada se detecta (exit 1) |
| Ejecución nueva tras migrar | ids 5, 7 y 8, mayores que el máximo migrado (4); repetir una migrada → `409` |
| 1. `requests.http` contra el servicio | 16/16 |
| 2. `chat` caído | `settings` → `503 SLHD_EVAL_00005`; guardar e historial responden |
| 2. Propagación a `chat` | el stub recibió `X-Request-Id` y `traceparent` |
| Paridad monolito vs. servicio (ejecución guardada, `settings` y errores) | iguales (con el monolito en modo `http`) |
| Healthcheck del compose, liveness, readiness y Prometheus | correctos en la imagen publicada |

Correcciones que salieron del ensayo:

- `requests.http`: el cuerpo repetido (#6) estaba incompleto y daba `422`; el #11 no traía todas las métricas por `k`.
- `compare-eval-baseline.ps1`: PowerShell 5.1 leía el JSON como Latin-1 y daba falsas diferencias.
- `infra/docker-compose.yml`: al monolito le faltaba `SHOPLAB_CHAT_SETTINGS_CLIENT_MODE=http` (dev sí lo fija).
- `contracts/http-api.md`: una propiedad desconocida es `400 SLHD_GENE_00002`, no `422 SLHD_EVAL_00002`.

## Dev: línea base y pasada previa de migración (2026-11-26)

Entorno de dev levantado con `make up`. El historial se alimentó con dos mediciones reales de recuperación
(`lab130-base-1` y `lab130-base-2`, 76 casos cada una) hechas con `shoplab-eval` por el stack local de compose, que
escribe en el mismo PostgreSQL que dev (en dev las claves de LiteLLM aún no existían).

- Rol y base `eval` creados con `eval-db-init`; `eval-service` quedó sano en dev.
- Línea base en [`baseline/monolith/`](baseline/monolith/) (respuestas HTTP del monolito de dev y `db-counts.txt`).
- Pasada previa (el guardado **no** estaba suspendido todavía): `migrate-eval` y `verify-eval-migration` con exit 0
  (`eval_run` 2, `eval_case` 76, `eval_result` 152; MD5 y secuencias iguales).
- `eval-service` de dev frente a la línea base: 0 diferencias.

### Bloqueo de capacidad en dev

El nodo tiene 1930m de CPU asignables y 1920m pedidos. `eval-service` pide 100m, los mismos que ClickHouse: ClickHouse
quedó `Pending`, el sync de `shoplab-dev` quedó en `Running` esperando su salud, y el hook `PostSync`
`litellm-bootstrap-keys` no creó las claves virtuales (retrieval respondía `503`, token no encontrado).

## Estado antes de levantar dev

- **El historial real está vacío**: `eval_run`, `eval_case` y `eval_result` tienen 0 filas en la base `shoplab` del
  host, que es la que usa dev. Con eso SC-001 se cumple sin comprobar nada y `compare` no tiene ids. Antes de T039
  conviene guardar al menos dos ejecuciones comparables con la herramienta contra el monolito.
- T026: el manifiesto `eval-service:v0.1.0` se descarga sin credenciales (HTTP 200).
- `secrets.auto.tfvars` ya tiene las claves `eval-db-password` y `eval` (solo se miraron los nombres; falta confirmar
  valores, subirlos con `make up` y crear rol y base: T042).
- El clúster de dev está apagado: T043–T051 no se pueden ejecutar.
- `infra/.env` local no define `EVAL_DB_PASSWORD` ni `SHOPLAB_EVAL_PERSISTENCE_TOKEN`.
