# Contract: ruleset de `main`

Contrato observable por desarrolladores y por GitHub. La fuente de verdad es
`.github/rulesets/main-protection.json`; este documento describe su semántica.

## Reglas

| Regla | Efecto observable |
|---|---|
| `deletion` | `git push origin :main` rechazado |
| `non_fast_forward` | force push a `main` rechazado |
| `required_linear_history` | no entran merge commits; historial lineal |
| `pull_request` (`allowed_merge_methods: ["squash"]`, 0 aprobaciones) | push directo rechazado; solo "Squash and merge" disponible |
| `required_status_checks` (estricto) | fusión bloqueada si faltan checks o la rama está desactualizada |
| `bypass_actors: []` | la regla también aplica al propietario |

## Checks requeridos

| Contexto | Workflow | Cubre | Se ejecuta cuando |
|---|---|---|---|
| `ci-gate` | `ci.yml` | `backend` (build, tests, JaCoCo), `qodana`, `trivy`, `frontend`, `python` | Siempre, en todo PR (`if: always()`) |
| `pr-title` | `pr-title.yml` | Título del PR | `opened`, `edited`, `synchronize`, `reopened` |

Un job omitido por el filtro de rutas no bloquea; uno fallido, cancelado o pendiente sí.

## Ajustes del repositorio (fuera del ruleset)

| Ajuste | Valor |
|---|---|
| `allow_merge_commit` / `allow_rebase_merge` | `false` |
| `squash_merge_commit_title` | `PR_TITLE` |
| `squash_merge_commit_message` | `BLANK` |
| `delete_branch_on_merge` | `true` |

## Cambios que rompen el contrato

Renombrar `ci-gate` o `pr-title`, o añadir un check requerido, exige actualizar el JSON del ruleset y
volver a aplicarlo en el mismo cambio.
