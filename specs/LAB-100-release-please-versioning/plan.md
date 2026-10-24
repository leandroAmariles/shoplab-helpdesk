# Implementation Plan: Versionado semántico automático con release-please

**Branch**: `chore/LAB-100-release-please-versioning` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-100-release-please-versioning/spec.md`

## Summary

El versionado automático se compone de cuatro piezas versionadas en el repositorio y una acción
manual única en GitHub:

1. **Workflow `release-please.yml`** (nuevo): en cada push a `main` ejecuta
   `googleapis/release-please-action@v4` (fijada por SHA del último v4.x) en modo manifest, con
   permisos mínimos y un token dedicado (`RELEASE_PLEASE_TOKEN`) para que el PR de release y el tag
   disparen otros workflows (CI y, más adelante, la imagen de LAB-78).
2. **`release-please-config.json` y `.release-please-manifest.json`** (nuevos, en la raíz): un único
   paquete raíz `.` que emite un solo tag `vX.Y.Z` y un `CHANGELOG.md` raíz; las versiones de los
   componentes se actualizan con `extra-files` (backend `pom.xml` ya; frontend `package.json` e
   ingest `pyproject.toml` cuando existan, ver research.md §1). Título del PR de release con la clave
   LAB para pasar `pr-title`.
3. **Primer release `v0.1.0`**: se fuerza con `release-as` temporal en la configuración y se retira
   en un PR posterior (research.md §4).
4. **Documentación**: sección "Versionado y releases" en el README, convención ampliada en
   `CLAUDE.md` y en `.ai/rules/development-guidelines.md` (§5), sin contradecir lo que ya fijó
   LAB-77.

La única acción manual fuera del código es crear el secreto `RELEASE_PLEASE_TOKEN` y habilitar
"Allow GitHub Actions to create and approve pull requests" (research.md §2). Las decisiones y
alternativas están en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions, JSON de configuración de release-please y Markdown. No
hay código de aplicación nuevo.

**Primary Dependencies**: `googleapis/release-please-action` v4 (fijada por SHA), token de acceso
personal de ámbito mínimo (`RELEASE_PLEASE_TOKEN`), `gh` para verificar; `ci.yml` y `pr-title.yml`
sin cambios de comportamiento.

**Storage**: N/A (el estado del release vive en `.release-please-manifest.json`, `CHANGELOG.md`, tags
y GitHub Releases).

**Testing**: Verificación extremo a extremo según [quickstart.md](quickstart.md): `actionlint` y
validación del JSON en local; en GitHub, PR de release generado por un `feat`, cálculo de versión
para `feat`/`fix`/`!`, ausencia de PR por `chore`/`docs`, idempotencia, CI y `pr-title` en verde
sobre el PR de release, fusión que crea `v0.1.0` y release con notas, y siguiente `fix` que propone
`v0.1.1`.

**Target Platform**: GitHub (repositorio `leandroAmariles/shoplab-helpdesk`), runner
`ubuntu-latest`.

**Project Type**: Infraestructura de CI/gobernanza de un monorepo; extiende LAB-15 y LAB-77.

**Performance Goals**: PR de release creado o actualizado en < 5 min tras el push; tag y GitHub
Release en < 5 min tras fusionar el PR (SC-001, SC-004).

**Constraints**: Permisos del workflow mínimos (`contents: write`, `pull-requests: write`,
`issues: write`, necesarios para tag, release y etiquetas de release-please); `concurrency` para no
solapar ejecuciones; el PR de release cumple las mismas reglas de `main` (checks `ci-gate` y
`pr-title`, squash, sin bypass); la rama del PR de release se llama
`release-please--branches--main` y no pasa por la regla de nombre de rama de trabajo; no se
modifican `ci.yml` ni `pr-title.yml` salvo lo estrictamente necesario.

**Scale/Scope**: 1 workflow, 2 JSON y 1 `CHANGELOG.md` nuevos (este último lo crea release-please),
ediciones a `pom.xml` (solo vía el PR de release), `README.md`, `CLAUDE.md` y
`.ai/rules/development-guidelines.md`, y un secreto de repositorio.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Aplica | Evaluación |
|---|---|---|
| I–III (arquitectura, dominio, errores) | No | No hay código de aplicación. |
| IV. Test-first con gate de cobertura | Parcial | Sin lógica de producción; la verificación es el quickstart ejecutable. El PR de release toca `backend/pom.xml`, así que `ci-gate` ejecuta `clean verify` completo y mantiene el gate de cobertura. |
| V. RAG evaluado | No | No toca prompts, chunking ni retrieval. |
| VI. Observabilidad y manejo seguro | Sí | El token es un secreto de GitHub, nunca en el repositorio; permisos mínimos; ninguna acción de terceros sin fijar por SHA. |
| VII. Simplicidad y decisiones documentadas | Sí | Se elige el diseño más simple que garantiza un único tag `vX.Y.Z` (paquete raíz + `extra-files`); la desviación respecto a "un paquete por componente" se justifica en research.md §1. Decisión relevante de proceso, sin ADR (reversible, sin impacto arquitectónico). |
| Flujo y commits | Sí | Título de PR `type(scope): [LAB-<n>] descripción` (LAB-77); el PR de release usa `chore(main): [LAB-100] release <versión>` para pasar `pr-title`. Se revisa que README y `CLAUDE.md` no contradigan la constitución (formato de commits de rama vs título del PR). |
| Stack | Sí | Backend Maven, frontend npm (Angular), ingest Python: mismos componentes que la constitución. |

**Resultado**: pasa, sin violaciones; no hace falta Complexity Tracking. Re-evaluado tras el diseño
de Phase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-100-release-please-versioning/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   ├── release-please-config.md
│   └── release-flow.md
├── checklists/
│   └── requirements.md  # /speckit-specify
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
.github/workflows/
└── release-please.yml            # nuevo: push a main → release-please-action@v4 (manifest)

release-please-config.json        # nuevo: paquete raíz, extra-files, título del PR de release
.release-please-manifest.json     # nuevo: versión actual del paquete raíz
CHANGELOG.md                      # lo crea y mantiene release-please (no se edita a mano)

backend/pom.xml                   # lo actualiza el PR de release (versión, sin -SNAPSHOT)

README.md                         # sección "Versionado y releases" + convenciones
CLAUDE.md                         # convención de versionado (§5)
.ai/rules/development-guidelines.md   # misma convención, fuente de CLAUDE.md
```

**Structure Decision**: Todo vive en la raíz y en `.github/workflows/`, igual que `ci.yml` y
`pr-title.yml`. No se crean carpetas nuevas ni se toca `src/`. `frontend/` e `ingest/` no se
modifican: añadir `package.json` o `pyproject.toml` ahora haría que los jobs de CI intentaran
instalar un proyecto inexistente (research.md §1).

## Complexity Tracking

Sin violaciones de la constitución que justificar.
