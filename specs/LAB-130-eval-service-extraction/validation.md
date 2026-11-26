# Validación: LAB-130

Evidencia por paso de [quickstart.md](quickstart.md). Estado al 2026-11-26.

## Estado de la implementación

| Fase | Estado | Evidencia |
|---|---|---|
| A. Servicio en paralelo (US1, US2) | **hecho** | `shoplab-eval-service` PR #5 y release PR #6 fusionados; `v0.1.0` publicada: imagen `ghcr.io/leandroamariles/eval-service:v0.1.0` firmada con Cosign y verificada, despliegue GitOps ejecutado, stubs `com.shoplab:shoplab-eval-service:0.1.0:stubs` publicados. CI: 430 pruebas, JaCoCo ≥ 80 %, Checkstyle, PMD, Qodana y Trivy en verde |
| B. Datos y despliegue (US3, parcial) | **hecho** | Scripts de migración/verificación probados y corte ensayado en aislado (abajo). Manifiestos `apps/eval-service` fusionados en `shoplab-infra` (PR #48, CI en verde) |
| C. Corte | **hecho** | Ver «Corte en dev» |
| US5. Documentación | hecho | ADR-0035, mapa de servicios, módulos, contratos, `admin-eval`, procedimiento de retiro del esquema |
| D. Retiro de `backend/` (US4) | **en PR** | Ver «Retiro de `backend/`»; faltan la fusión, `shoplab-infra` y la imagen en GHCR |

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

## Corte en dev (2026-11-26, horas UTC)

| Tarea | Resultado |
|---|---|
| Capacidad | `shoplab-infra` #49: CPU de `eval-service` 100m → 25m y de `gateway` 100m → 50m, `maxSurge: 0`. ClickHouse y `litellm-bootstrap-keys` se programaron y las aplicaciones de Argo quedaron `Synced/Healthy` |
| T044 | Guardado suspendido con `shoplab-infra` #50: `POST /api/eval/runs` → `403 SLHD_EVAL_00001`, historial `200`. 14:24: `migrate-eval --replace` y `verify-eval-migration` exit 0 (2 / 76 / 152 filas, MD5 y secuencias iguales) |
| T045 | `eval-service` frente a la línea base: 0 diferencias (bash y PowerShell) |
| T046 | `shoplab-infra` #51 fusionado; 14:26 el gateway llega a `eval-service`. Hubo unos segundos de `503` de nginx durante el relevo del gateway (`maxSurge: 0`) |
| T047 | `shoplab-eval retrieval --save` real contra el gateway, sin tocar su código (solo URL, cabecera `Host` y token): `lab130-post-cut`, 76 resultados |
| T048 | `admin-eval` en Edge sin interfaz: lista las 2 migradas y la nueva, abre el detalle y compara `lab130-base-1` con `lab130-post-cut` (API 200). 0 cambios en `shoplab-frontend` |
| T049 | La ejecución nueva recibió `id=3` (máximo migrado 2) y solo existe en la base `eval` |
| T050 | Reversión con `shoplab-infra` #52: CI 39 s y monolito atendiendo 21 s después de fusionar (muestra solo las 2 migradas). Corte rehecho con #53 |
| T051 | `GET /api/eval/settings` por el gateway devuelve la configuración de `chat-service` con los prompts de Langfuse |

Al revertir, lo guardado después del corte no se ve en el monolito: vive solo en la base `eval`.

## Retiro de `backend/` (2026-11-26)

| Tarea | Resultado |
|---|---|
| T057 | Tag `backend-final-pre-LAB-130` publicado; `pg_dump` de la base `eval` y del esquema `eval` de `shoplab` en `shoplab-backups/LAB-130/` (fuera del repositorio); restauración en ADR-0035 |
| T058 | Inventario abajo |
| T059–T061 | `backend/` eliminado (208 archivos); fuera `images.yml`, `publish-stubs.yml`, `.github/scripts` (solo desplegaban la imagen del monolito), jobs `backend` y `qodana`, entradas de Dependabot y release-please, servicio del compose, job de Prometheus y la clave `backend` del gateway de modelos |
| T063 | Proyecto de compose desechable con volúmenes nuevos y `.env.example`: todos los servicios `healthy` sin el monolito; historial `200` por gateway y frontend; claves `chat`, `retrieval`, `ingest` y `eval` creadas. Solo se reutilizó el volumen de modelos de Ollama |
| T064 | `catalog`, `orders`, `tickets` y `eval` presentes (`orders.orders` 99441 filas, `catalog.products` 32951, `eval.eval_run` 2); ningún `DROP SCHEMA` nuevo en el PR |
| T065 | Constitución 1.0.3, `.ai/context/project-context.md` y `README.md` |
| Pruebas | `infra/litellm` 81, `notebooks` 111, dashboards y perfiles del compose válidos |

### Inventario de referencias antes del retiro (archivo: líneas con «backend», fuera de `backend/`, specs y ADR)

- `README.md`: 35
- `docs/observabilidad.md`: 15
- `infra/litellm/tests/test_bootstrap_keys.py`: 14
- `.github/workflows/ci.yml`: 13
- `docs/chat-streaming.md`: 12
- `docs/gateway-de-modelos.md`: 12
- `infra/docker-compose.yml`: 12
- `docs/fases/F5-entorno-local-multi-repo.md`: 9
- `infra/.env.example`: 9
- `.github/scripts/tests/deploy-dev.test.sh`: 6
- `.github/scripts/tests/fixtures/infra/k8s/base/backend/deployment.yaml`: 5
- `docs/olist-dataset.md`: 5
- `infra/litellm/keys.yaml`: 5
- `.agents/skills/code-context-builder/SKILL.md`: 4
- `.ai/skills/code-context-builder/SKILL.md`: 4
- `.claude/skills/code-context-builder/SKILL.md`: 4
- `.github/workflows/images.yml`: 4
- `.github/workflows/publish-stubs.yml`: 4
- `.specify/memory/constitution.md`: 4
- `data/olist/README.md`: 4
- `docs/fases/F5-mover-rutas-al-gateway.md`: 4
- `docs/modulos.md`: 4
- `infra/docker-compose.override.yml.example`: 4
- `infra/litellm/tests/test_shoplab_callbacks.py`: 4
- `infra/observability/dashboards/pods.json`: 4
- `.ai/context/project-context.md`: 3
- `.github/dependabot.yml`: 3
- `.specify/templates/plan-template.md`: 3
- `data/olist/tests/test_preflight.py`: 3
- `infra/observability/dashboards/http.json`: 3
- `infra/observability/dashboards/jvm.json`: 3
- `infra/observability/prometheus/prometheus.yml`: 3
- `ml/README.md`: 3
- `data/olist/src/olist_loader/preflight.py`: 2
- `docs/admin-eval.md`: 2
- `docs/contratos.md`: 2
- `docs/despliegue-continuo.md`: 2
- `docs/mapa-servicios.md`: 2
- `infra/litellm/config.yaml`: 2
- `scripts/test-e2e.sh`: 2
- `.gitattributes`: 1
- `.github/scripts/tests/fixtures/infra/k8s/base/kustomization.yaml`: 1
- `.github/scripts/tests/fixtures/infra/k8s/overlays/dev/kustomization.yaml`: 1
- `.specify/templates/tasks-template.md`: 1
- `.trivyignore`: 1
- `data/kb-tools/README.md`: 1
- `data/kb-tools/pyproject.toml`: 1
- `data/olist/pyproject.toml`: 1
- `data/olist/src/olist_loader/config.py`: 1
- `data/olist/src/olist_loader/errors.py`: 1
- `data/olist/tests/test_cli.py`: 1
- `data/olist/tests/test_migrations.py`: 1
- `docs/fases/F5-contratos-http-versionado-y-retiro.md`: 1
- `infra/litellm/bootstrap_keys.py`: 1
- `infra/litellm/tests/test_render_config.py`: 1
- `ml/ci/prepare-database.sh`: 1
- `ml/data/intents/DATASHEET.md`: 1
- `ml/pyproject.toml`: 1
- `ml/src/intent_dataset/sources.py`: 1
- `ml/src/olist_eda/config.py`: 1
- `ml/src/olist_eda/db.py`: 1
- `ml/tests/test_config.py`: 1
- `ml/tests/test_db.py`: 1
- `notebooks/README.md`: 1
- `notebooks/pyproject.toml`: 1
- `notebooks/tests/test_notebook.py`: 1
- `notebooks/vendor/ingest_shim/app/openapi_export.py`: 1
- `release-please-config.json`: 1
- `scripts/test-e2e.ps1`: 1

Las referencias a `backend/` que quedan describen su retiro, son ejemplos genéricos de las plantillas de spec-kit o
un paquete Java ajeno en la skill `code-context-builder`.

## Estado antes de levantar dev

- **El historial real está vacío**: `eval_run`, `eval_case` y `eval_result` tienen 0 filas en la base `shoplab` del
  host, que es la que usa dev. Con eso SC-001 se cumple sin comprobar nada y `compare` no tiene ids. Antes de T039
  conviene guardar al menos dos ejecuciones comparables con la herramienta contra el monolito.
- T026: el manifiesto `eval-service:v0.1.0` se descarga sin credenciales (HTTP 200).
- `secrets.auto.tfvars` ya tiene las claves `eval-db-password` y `eval` (solo se miraron los nombres; falta confirmar
  valores, subirlos con `make up` y crear rol y base: T042).
- El clúster de dev está apagado: T043–T051 no se pueden ejecutar.
- `infra/.env` local no define `EVAL_DB_PASSWORD` ni `SHOPLAB_EVAL_PERSISTENCE_TOKEN`.
