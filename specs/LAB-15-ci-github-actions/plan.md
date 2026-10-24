# Implementation Plan: Pipeline de CI en GitHub Actions para Java, Angular y Python

**Branch**: `chore/LAB-15-ci-github-actions` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-15-ci-github-actions/spec.md`

## Summary

Un único workflow `.github/workflows/ci.yml` que se dispara en cada pull request y en cada push a
`main`. Un job inicial calcula qué componentes cambiaron (filtros por ruta evaluados dentro del
workflow, no con `paths:` a nivel de trigger) y los jobs `backend`, `frontend`, `ingest` y `eval`
se ejecutan solo si les corresponde. Un job final `ci-gate` agrega los resultados y es el único
check que se exige en la protección de rama; así un componente omitido nunca deja el PR
pendiente. El job Java ejecuta `./mvnw clean verify` (Checkstyle, PMD, Modulith, JaCoCo >= 80 %,
pruebas de integración) y sube el reporte de cobertura aunque falle. Angular y Python detectan si su
proyecto existe y, si no, terminan en verde sin trabajo. Se añade el badge al README y se corrige el
permiso de ejecución de `backend/mvnw`. Decisiones en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions. Componentes validados: Java 21 (Temurin), Node 22 LTS
(Angular), Python según `requires-python` del proyecto (gestionado por uv).

**Primary Dependencies**: `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`,
`actions/upload-artifact@v4`, `astral-sh/setup-uv@v6`, `dorny/paths-filter@v3`.

**Storage**: N/A. Artefactos de ejecución (reporte JaCoCo) y cachés gestionados por la plataforma.

**Testing**: El propio pipeline se valida con PRs de prueba (ver [quickstart.md](quickstart.md));
`actionlint` local opcional. No hay código de aplicación nuevo.

**Target Platform**: Runner `ubuntu-latest` hospedado por GitHub (incluye daemon Docker).

**Project Type**: Infraestructura de CI de un monorepo (backend / frontend / ingest / eval).

**Performance Goals**: Backend con caché en < 10 min (SC-004); PR solo de docs sin builds (SC-003).

**Constraints**: Sin secretos ni permisos de escritura (`permissions: contents: read`); compatible
con PRs desde forks; checks estables por componente; versiones de acciones fijadas por tag mayor.

**Scale/Scope**: 1 workflow, 6 jobs, 1 cambio en README, 1 cambio de modo de archivo (`mvnw`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Estado | Nota |
|---|---|---|
| I. Monolito modular primero | Pass | CI ejecuta el test de Modulith vía `verify`; no toca módulos. |
| II. Hexagonal / dominio puro | N/A | Sin código de aplicación. |
| III. Identificadores en inglés | Pass | Jobs y archivos en inglés; documentación en español. |
| IV. Test-first con gate 80 % (NON-NEGOTIABLE) | Pass | El gate `jacoco:check` pasa a ser bloqueante en CI (FR-008). |
| V. RAG evaluado por PR (desde F2) | N/A | Fuera de alcance; el pipeline queda como base para F2. |
| VI. Observabilidad y secretos | Pass | Sin secretos; permisos mínimos; sin datos reales. |
| VII. Simplicidad y ADR | Pass | Un solo workflow; solo dos acciones de terceros (`paths-filter`, `setup-uv`). Sin decisión arquitectónica que amerite ADR. |
| Branch/commit naming | Pass | `chore/LAB-15-ci-github-actions`; directorio igual al último segmento. |

**Re-check post-diseño**: Pass. Sin violaciones; Complexity Tracking vacío.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-15-ci-github-actions/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── ci-checks.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no creado aquí)
```

### Source Code (repository root)

```text
.github/
└── workflows/
    └── ci.yml           # nuevo: changes, backend, frontend, python, ci-gate
README.md                # badge de estado del pipeline
.gitattributes           # mvnw con eol=lf (evita CRLF que rompe el shebang en Linux)
backend/mvnw             # modo de archivo 100755 en git (solo cambia el permiso)
```

**Structure Decision**: Un workflow único con jobs por componente y filtrado interno por ruta.
Alternativa descartada: un workflow por componente con `paths:` en el trigger, porque un check
requerido que no se dispara deja el PR bloqueado (ver research.md, decisión 1).

## Complexity Tracking

Sin violaciones de la constitución que justificar.
