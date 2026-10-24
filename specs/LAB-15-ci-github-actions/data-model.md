# Data Model: Pipeline de CI

No hay datos persistentes de aplicación. Las entidades describen los elementos del pipeline.

## Workflow `CI`

| Campo | Valor |
|---|---|
| Archivo | `.github/workflows/ci.yml` |
| Disparadores | `pull_request`; `push` a `main` |
| Permisos | `contents: read` |
| Concurrencia | grupo por workflow + ref; cancela solo en `pull_request` |

## Filtro por ruta (salida del job `changes`)

| Salida | Rutas que la activan |
|---|---|
| `backend` | `backend/**` |
| `frontend` | `frontend/**` |
| `ingest` | `ingest/**` |
| `eval` | `eval/**` |
| `ci` | `.github/workflows/ci.yml` |

Regla: un job de componente corre si su salida o `ci` es `true` (FR-003, FR-004).

## Job por componente

| Job | Condición de ejecución | Resultado si no hay proyecto | Artefactos |
|---|---|---|---|
| `backend` | filtro `backend` o `ci` | N/A (el proyecto existe) | `jacoco-report` |
| `frontend` | filtro `frontend` o `ci` | Verde con aviso | ninguno |
| `python` (matriz `ingest`, `eval`) | su filtro o `ci` | Verde con aviso | ninguno |
| `ci-gate` | siempre | N/A | ninguno |

### Transiciones de resultado

`queued -> in_progress -> success | failure | cancelled`; un job condicionado puede terminar en
`skipped`, que `ci-gate` trata como válido. `ci-gate` falla si algún job es `failure` o `cancelled`.

## Reporte de cobertura

| Campo | Valor |
|---|---|
| Nombre del artefacto | `jacoco-report` |
| Origen | `backend/target/site/jacoco/` |
| Cuándo se sube | siempre (`if: always()`) |
| Retención | 14 días |
| Regla de validación | cobertura de líneas del bundle >= 0.80 (`jacoco.minimum.coverage`) |

## Badge de estado

Imagen del workflow `ci.yml` en la rama `main`, enlazada a sus ejecuciones.
