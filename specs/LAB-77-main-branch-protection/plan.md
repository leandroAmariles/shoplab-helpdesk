# Implementation Plan: Protección de main con checks requeridos

**Branch**: `chore/LAB-77-main-branch-protection` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-77-main-branch-protection/spec.md`

## Summary

La protección de `main` se compone de tres piezas versionadas en el repositorio y una activación en
GitHub:

1. **Workflow `pr-title.yml`** (nuevo): job `pr-title` con `amannn/action-semantic-pull-request`, que
   valida el título del PR (`type(scope): [LAB-<n>] descripción`) en `opened`, `edited`, `synchronize`
   y `reopened`. Es un workflow aparte de `ci.yml` para que editar el título no relance todo el CI.
2. **Ruleset `main-protection.json`** (nuevo, en `.github/rulesets/`, nombre `main-protect` como el ya existente): sin borrado ni force push, PR
   obligatorio con squash como único método, historial lineal, rama al día
   (`strict_required_status_checks_policy`), sin bypass y checks requeridos `ci-gate` y `pr-title`.
   `ci-gate` ya agrega `backend` (build, tests, JaCoCo), `qodana` y `trivy` y trata los jobs omitidos
   por filtro de rutas como satisfechos, así que exigirlo cubre los tres checks de la US sin
   bloquear PRs que no tocan un componente.
3. **Ajustes del repositorio**: solo squash merge y título del PR como título del commit de squash
   (`squash_merge_commit_title: PR_TITLE`), aplicados con `gh api` desde comandos documentados.
4. **README y plantilla de PR**: flujo de contribución, formato del título, lista de checks
   requeridos y cómo aplicar o restaurar el ruleset; se reconcilia con el formato de commit existente.

Las decisiones y alternativas están en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions, JSON de ruleset de GitHub y Markdown. No hay código de
aplicación nuevo.

**Primary Dependencies**: `amannn/action-semantic-pull-request` (fijada por SHA de commit, ver
research.md), GitHub CLI `gh` para aplicar el ruleset, `ci.yml` de LAB-15/LAB-75/LAB-76 (sin cambios
de comportamiento).

**Storage**: N/A.

**Testing**: Validación con PRs de prueba (ver [quickstart.md](quickstart.md)): títulos válidos e
inválidos, edición del título sin commit nuevo, PR con check fallido bloqueado, PR solo de docs
fusionable, push directo y force push rechazados, segundo PR obligado a actualizarse. `actionlint`
local sobre el workflow y `gh api` para comparar el ruleset activo con el versionado.

**Target Platform**: GitHub (repositorio `leandroAmariles/shoplab-helpdesk`), runner
`ubuntu-latest`.

**Project Type**: Infraestructura de CI/gobernanza de un monorepo; extiende el pipeline de LAB-15.

**Performance Goals**: Check de título en < 1 min; reevaluación tras editar el título en < 2 min
(SC-005).

**Constraints**: Permisos mínimos (`pull-requests: read`); el workflow no hace checkout ni ejecuta
código del PR; nombres de check estables (`ci-gate`, `pr-title`); sin bypass; el ruleset solo puede
activarse tras fusionar esta historia (el check `pr-title` debe existir en `main`).

**Scale/Scope**: 1 workflow y 1 JSON nuevos, ediciones menores a `README.md` y
`pull_request_template.md`, y una operación de activación en GitHub.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Estado | Nota |
|---|---|---|
| I. Monolito modular primero | N/A | No toca módulos. |
| II. Hexagonal / dominio puro | N/A | Sin código de aplicación. |
| III. Identificadores en inglés | Pass | Jobs, archivos y claves en inglés; documentación en español. |
| IV. Test-first con gate 80 % | Pass | No altera JaCoCo ni los tests; lo hace obligatorio vía `ci-gate`. |
| V. RAG evaluado por PR (desde F2) | N/A | Fuera de alcance; cuando exista su job se añadirá a `ci-gate`. |
| VI. Observabilidad y secretos | Pass | Sin secretos; solo `GITHUB_TOKEN` con permisos mínimos; no se ejecuta código del PR. |
| VII. Simplicidad y ADR | Pass | Un check requerido agregador en vez de N checks; sin script de aplicación (dos comandos `gh`). Se evalúa en research.md si ameritaría ADR: no, es política de repositorio reversible. |
| Workflow: ramas y commits | Pass | `chore/LAB-77-main-branch-protection`; directorio igual al último segmento. Formalizar el título del PR complementa la regla de commits (ver Assumptions de la spec). |
| PR con plantilla | Pass | Se actualiza la plantilla con el formato del título. |

**Re-check post-diseño**: Pass. Sin violaciones; Complexity Tracking vacío.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-77-main-branch-protection/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── main-ruleset.md
│   └── pr-title-check.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no creado aquí)
```

### Source Code (repository root)

```text
.github/
├── rulesets/
│   └── main-protection.json     # nuevo: definición versionada del ruleset de main
├── workflows/
│   ├── ci.yml                   # sin cambios
│   └── pr-title.yml             # nuevo: job `pr-title`
└── pull_request_template.md     # + formato del título del PR
README.md                        # + "Protección de main" y reconciliación de convenciones
```

**Structure Decision**: Workflow separado para el título porque necesita el evento `edited`, que en
`ci.yml` relanzaría build, Qodana y Trivy por cada cambio de título (research.md, decisión 2). El
ruleset va en `.github/rulesets/` como JSON exportable/importable con `gh api`, y es la fuente de
verdad auditable (research.md, decisión 5).

## Complexity Tracking

Sin violaciones de la constitución que justificar.
