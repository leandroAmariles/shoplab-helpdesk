# Validación: consolidar shoplab-infra en un solo ambiente dev

**Fecha**: 2026-10-26 | **Rama (`shoplab-infra`)**: `chore/LAB-101-single-dev-environment`, creada desde `main` en
`bbb64ee` | **Estado**: implementación local completa; validación en vivo y CI pendientes.

## Entorno de la verificación local

Herramientas presentes: `kustomize` 5.8.1 (el repo pide 5.8.2; se usó `ALLOW_TOOL_DRIFT=1`), `terraform` 1.15.8, `tflint`,
`docker`, `kubectl`, bash. **Faltan** `make`, `kubeconform` y `yq`, de modo que `make check` completo no se pudo ejecutar
en este equipo. No se instalaron binarios de terceros para suplirlo; esa parte la cubre la CI del PR.

## Resultados

| Comprobación | Tarea | Resultado |
|---|---|---|
| No había entorno local anterior que destruir (sin contenedores `shoplab-floci`, `floci-aws-*` ni `shoplab-local-*`) | T004 | OK |
| `kustomize build k8s/overlays/dev` antes y después del cambio | T008 | Idéntico (diferencia cero, 747 líneas) |
| `kustomize build k8s/argocd` | T009 | Una sola `Application` (`shoplab-dev`); no incluye `shoplab-root` |
| Residuos de `staging`, `prod`, `argocd-local` en `k8s/` | T010 | Ninguno |
| `.github/` sin menciones a `staging`/`prod` (jobs y contextos `ci-gate` y `pr-title` sin cambios) | T016 | OK |
| `bash -n` de `kustomize-build.sh` y `smoke.sh` | T012–T013 | OK |
| `scripts/test.sh fmt` (`terraform fmt -check`) | T024 | OK |
| `scripts/test.sh checks` (estructura: 1 ambiente; pines de versión) | T024 | OK |
| `scripts/test.sh validate` (módulos, `envs/dev` y `envs/dev/bootstrap`) | T024 | OK |
| `scripts/test.sh modules` (`terraform test`: eks 5, platform 13, secrets 4, state 2) | T024 | 24 pasan, 0 fallan |
| `scripts/lint.sh` (TFLint recursivo) | T024 | OK |
| Búsqueda de `envs/local`, `shoplab-local`, `app_environments`, `"local"` en `terraform/`, `scripts/`, `Makefile`, `.github/`, `versions.env` | T025 | Ninguno |
| Búsqueda de `staging\|prod\|argocd-local\|envs/local\|shoplab-local` en `shoplab-infra` | T041 | Solo las secciones de migración de `docs/gitops.md` y `docs/terraform-local.md` (FR-014) |
| Misma búsqueda en `shoplab-helpdesk` (sin `specs/`) | T041 | Solo ADR-0006/0007 (históricos, con su nota de estado) y ADR-0008 |

## Pendiente

| Tarea | Motivo | Cómo completarla |
|---|---|---|
| T002, T014, T015, T027 | Faltan `make`, `kubeconform` y `yq` en este equipo | Se ejecutan en la CI del PR (`kustomize`, `static`, `smoke`); o instalando las herramientas de `versions.env` |
| T026 | Necesita el emulador levantado | `make test-integration` |
| T028–T031 | Argo CD sincroniza `main` desde GitHub, y `main` aún tiene `staging`, `prod` y `argocd-local`. Con la raíz nueva (`k8s/argocd`) un `make up` ahora crearía las Applications antiguas. | Publicar la rama y ejecutar `make smoke` con `SMOKE_ROOT_REVISION=<sha de la rama>` (lo hace la CI), o fusionar y luego `make up` desde cero y registrar aquí `kubectl -n argocd get applications` |
| T042–T046 | No se han hecho commits, push ni PR (no solicitados) | Skill `commit-and-push` en `shoplab-infra` y luego en `shoplab-helpdesk` |

## Limitaciones conocidas a tener en cuenta en la validación en vivo

- El k3s de Floci no trae `StorageClass`: `shoplab-dev` puede quedar `Progressing` por los PVC de Postgres y Ollama
  (ver `docs/kustomize.md`). Si ocurre, basta con `Synced` sin `ComparisonError`.
- Si existe un entorno local anterior, destruirlo con la versión previa antes de actualizar
  (`docs/terraform-local.md`, "Pasar desde el entorno `local` anterior").
