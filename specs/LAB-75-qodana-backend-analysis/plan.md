# Implementation Plan: Análisis estático con Qodana en el backend

**Branch**: `chore/LAB-75-qodana-backend-analysis` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-75-qodana-backend-analysis/spec.md`

## Summary

Se añade un job `qodana` al workflow existente `.github/workflows/ci.yml` (LAB-15), reutilizando su
job `changes` (filtro `backend/**` o cambio del workflow) y sumándolo a las dependencias de
`ci-gate`, de modo que el único check exigido en la protección de rama sigue siendo `ci-gate`. El
job ejecuta `JetBrains/qodana-action` sobre `backend/` con el linter JVM Community (sin token),
configurado por `backend/qodana.yaml` (perfil `qodana.recommended`, exclusión de código generado,
quality gate por severidad) y comparado contra un baseline SARIF versionado
(`backend/qodana.sarif.json`). Los resultados se publican como anotaciones y comentario del PR (la
propia acción) y en la pestaña Security con `github/codeql-action/upload-sarif`. En PRs desde forks
se omiten las publicaciones que requieren escritura. Decisiones en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions + YAML de Qodana. Código analizado: Java 21 (Temurin),
Maven, Spring Boot 4.x.

**Primary Dependencies**: `JetBrains/qodana-action@v2026.2.2`,
`github/codeql-action/upload-sarif@v4`, `actions/checkout@v4`, `actions/setup-java@v4` (ya usadas en
LAB-15). Linter: `qodana-jvm-community`.

**Storage**: N/A. Baseline SARIF versionado en git; caché de Qodana gestionada por la acción
(`use-caches`).

**Testing**: Validación del propio pipeline con PRs de prueba (ver [quickstart.md](quickstart.md)):
PR limpio (verde), PR con un problema de severidad alta (rojo), PR de docs (job omitido).
`actionlint` local opcional. No hay código de aplicación nuevo, por lo que JaCoCo no se ve afectado.

**Target Platform**: Runner `ubuntu-latest` hospedado por GitHub (Docker disponible para el linter).

**Project Type**: Infraestructura de CI de un monorepo; extiende el pipeline de LAB-15.

**Performance Goals**: Análisis de un PR típico del backend en < 15 min (SC-007) y sin consumir
ejecuciones en PRs ajenos al backend (SC-006).

**Constraints**: Sin secretos obligatorios (`QODANA_TOKEN` opcional, nunca en código); permisos
mínimos por job; PRs desde forks sin escritura; formato de líneas de Java sin tocar (no se modifica
código). Versiones de acciones fijadas por tag mayor/calendario, como en LAB-15.

**Scale/Scope**: 1 job nuevo en el workflow, 1 cambio en `ci-gate`, 2 archivos nuevos en `backend/`
(`qodana.yaml`, `qodana.sarif.json`), una nota en README/docs sobre cómo regenerar el baseline.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Estado | Nota |
|---|---|---|
| I. Monolito modular primero | N/A | No toca módulos ni sus límites. |
| II. Hexagonal / dominio puro | N/A | Sin código de aplicación. |
| III. Identificadores en inglés | Pass | Job y archivos en inglés; documentación en español. |
| IV. Test-first con gate 80 % | Pass | No altera JaCoCo ni los tests; el análisis es adicional. |
| V. RAG evaluado por PR (desde F2) | N/A | Fuera de alcance. |
| VI. Observabilidad y secretos | Pass | `QODANA_TOKEN` solo como secreto opcional; permisos mínimos; sin datos reales. |
| VII. Simplicidad y ADR | Pass | Un job en el workflow existente, sin workflow nuevo; Community sin token. Sin decisión arquitectónica que amerite ADR. |
| Style gates (PMD/Checkstyle) | Pass | Sin código Java modificado. |
| Branch/commit naming | Pass | `chore/LAB-75-qodana-backend-analysis`; directorio igual al último segmento; commits `LAB-75 type(scope): ...`. |

**Re-check post-diseño**: Pass. Sin violaciones; Complexity Tracking vacío.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-75-qodana-backend-analysis/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── qodana-check.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no creado aquí)
```

### Source Code (repository root)

```text
.github/
└── workflows/
    └── ci.yml               # + job `qodana`; `ci-gate` lo incluye en `needs`
backend/
├── qodana.yaml              # nuevo: linter, perfil, exclusiones, quality gate
└── qodana.sarif.json        # nuevo: baseline inicial (se genera con una primera ejecución)
README.md                    # nota breve: análisis Qodana y cómo regenerar el baseline
```

**Structure Decision**: Job dentro de `ci.yml` y no un workflow aparte, para heredar los filtros por
ruta, la concurrencia y el check agregador `ci-gate` de LAB-15 (research.md, decisión 1). La
configuración vive en `backend/` porque el análisis se limita a ese proyecto.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
