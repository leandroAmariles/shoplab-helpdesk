# Data Model: Protección de main

No hay persistencia propia: las entidades son archivos versionados y configuración de GitHub.

## Ruleset de `main`

| Campo | Valor |
|---|---|
| nombre | `main-protect` (ya existe en el repositorio; se actualiza, no se duplica) |
| objetivo | rama por defecto (`~DEFAULT_BRANCH`) |
| enforcement | `active` |
| bypass | ninguno (`bypass_actors: []`) |
| reglas | `deletion`, `non_fast_forward`, `required_linear_history`, `pull_request`, `required_status_checks` |
| métodos de fusión | solo `squash` |
| aprobaciones requeridas | 0 |
| checks requeridos | `ci-gate`, `pr-title` (app GitHub Actions) |
| rama al día | sí (`strict_required_status_checks_policy: true`) |

## Check requerido

| Campo | Descripción |
|---|---|
| nombre | contexto estable (`ci-gate` o `pr-title`) |
| origen | job del workflow `ci.yml` o `pr-title.yml` |
| estado | pendiente / éxito / fallo / cancelado / omitido |

Regla: la fusión se habilita ⇔ cada check requerido está en `éxito` u `omitido` sobre el último commit
del PR ∧ la rama está al día con `main`. `pendiente`, `fallo` y `cancelado` bloquean. Un check
ausente (nunca reportado) bloquea.

## Título de PR

| Campo | Descripción |
|---|---|
| type | uno de `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert` |
| scope | opcional, entre paréntesis |
| clave | `[LAB-<n>]`, al inicio de la descripción; obligatoria salvo para `dependabot[bot]` |
| descripción | texto libre no vacío |

Gramática: `type(scope)?: [LAB-<n>] descripción`.

Transiciones del check `pr-title`: `opened | reopened | synchronize | edited → evaluado →
éxito | fallo`. Editar el título reinicia la evaluación sin nuevo commit.

## Commit de squash

| Campo | Descripción |
|---|---|
| título | título del PR (`squash_merge_commit_title: PR_TITLE`) |
| cuerpo | vacío (`squash_merge_commit_message: BLANK`) |
| padre | único, el tip previo de `main` (historial lineal) |

## Ajustes del repositorio

| Ajuste | Valor |
|---|---|
| `allow_squash_merge` | `true` |
| `allow_merge_commit` | `false` |
| `allow_rebase_merge` | `false` |
| `delete_branch_on_merge` | `true` |
