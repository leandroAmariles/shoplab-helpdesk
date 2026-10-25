# Contract: workflow de CI y ruleset de `shoplab-infra`

Contrato observable por desarrolladores y por GitHub. Fuentes de verdad: `.github/workflows/ci.yml` y
`.github/rulesets/main-protection.json` en `shoplab-infra`.

## Disparadores

| Evento | Detalle |
|---|---|
| `pull_request` | Todo PR hacia `main` |
| `push` | Solo `main` |
| `workflow_dispatch` | Permite correr la prueba de humo a demanda |

`concurrency`: grupo por workflow y ref; `cancel-in-progress` solo en `pull_request`.

## Jobs

| Job | Cubre (spec) | Corre cuando | Permisos | Falla si |
|---|---|---|---|---|
| `changes` | filtro de rutas | siempre | `contents: read` | error del filtro |
| `static` | FR-002/003/004 | cambian `terraform/`, `.tflint.hcl`, `versions.env` o el workflow | `contents: read` | `fmt -check`, `validate`, TFLint o `terraform test` fallan |
| `kustomize` | FR-007/008/009/010/011/012 | cambian `kubernetes/`, `scripts/` o el workflow | `contents: read` | overlay ausente/vacío/inválido o violación de política |
| `trivy` | FR-013/014 | siempre | `contents: read`, `security-events: write` | hallazgos HIGH/CRITICAL no exceptuados |
| `plan` | FR-005/006 | cambian `terraform/` o el workflow | `contents: read`, `pull-requests: write` | Floci no listo en el tiempo límite o `plan` falla |
| `smoke` | FR-015/016 | cambian `terraform/`, `kubernetes/`, `scripts/` o el workflow; push a `main`; `workflow_dispatch` | `contents: read` | apply falla, Argo CD no queda Synced+Healthy a tiempo o la limpieza falla |
| `ci-gate` | FR-018 | siempre (`if: always()`) | ninguno | algún `needs` en `failure` o `cancelled` |

Un job omitido por el filtro de rutas no bloquea; uno fallido, cancelado o pendiente sí.

## Checks requeridos del ruleset

| Contexto | Workflow | Cubre |
|---|---|---|
| `ci-gate` | `ci.yml` | `static`, `kustomize`, `trivy`, `plan`, `smoke` |
| `pr-title` | `pr-title.yml` | Título del PR con Conventional Commits y clave LAB |

## Ruleset (equivalente al de la app)

| Regla | Efecto observable |
|---|---|
| `deletion` | `git push origin :main` rechazado |
| `non_fast_forward` | force push rechazado |
| `required_linear_history` | historial lineal |
| `pull_request` (`allowed_merge_methods: ["squash"]`, 0 aprobaciones) | push directo rechazado; solo squash |
| `required_status_checks` (estricto) | fusión bloqueada con checks faltantes o rama desactualizada |
| `bypass_actors: []` | aplica también al propietario |

Ajustes del repositorio: `allow_merge_commit=false`, `allow_rebase_merge=false`,
`squash_merge_commit_title=PR_TITLE`, `squash_merge_commit_message=BLANK`, `delete_branch_on_merge=true`.

Diferencia admitida respecto al repo de la app: ninguna en las reglas; solo cambia el contenido que cubre `ci-gate`.

## Comentario del plan

- Marcador: `<!-- shoplab-infra-tf-plan -->` en la primera línea; un único comentario por PR (se actualiza).
- Cuerpo: resumen (`N to add, N to change, N to destroy`) y el plan en un bloque de código; si supera ~60 000
  caracteres se trunca con aviso y enlace a la ejecución.
- Sin permiso de escritura: el mismo contenido va a `$GITHUB_STEP_SUMMARY`.

## Salidas hacia Security

SARIF de Trivy con `category: trivy-config`, subido con `if: always()` y solo cuando el token tiene escritura
(no en forks ni Dependabot).

## Cambios que rompen el contrato

Renombrar `ci-gate` o `pr-title`, o añadir un check requerido, exige actualizar el JSON del ruleset y volver a
aplicarlo en el mismo cambio.
