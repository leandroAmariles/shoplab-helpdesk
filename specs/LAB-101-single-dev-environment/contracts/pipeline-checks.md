# Contrato: pipeline de `shoplab-infra` tras el cambio

Qué construye y exige el pipeline con un solo ambiente (FR-004, FR-005).

## Verificaciones obligatorias (protección de `main`)

| Contexto exigido | Quién lo reporta | Cambio |
|---|---|---|
| `ci-gate` | job `ci-gate` de `.github/workflows/ci.yml` (`needs: changes, static, kustomize, trivy, plan, smoke`) | **ninguno**: mismo nombre y mismos `needs` |
| `pr-title` | `.github/workflows/pr-title.yml` | ninguno |

`.github/rulesets/main-protection.json` no se modifica. Los jobs `static`, `kustomize`, `trivy`, `plan`,
`smoke` conservan nombre; solo cambia lo que ejecutan algunos scripts.

## Configuración (`versions.env`)

| Clave | Valor final |
|---|---|
| `OVERLAYS` | `dev` |
| `KUSTOMIZE_DIR` | `k8s/overlays` |
| `ARGOCD_DIR` | `k8s/argocd` |
| `ARGOCD_READY_APPS` | `shoplab-root` |
| `ARGOCD_PRESENT_APPS` | `shoplab-dev` |

El comentario de `OVERLAYS` ("Overlays de Kustomize que valida el pipeline") se mantiene; se ajusta el
de `ARGOCD_DIR` ("una Application por ambiente") a un solo ambiente.

## Qué hace cada script afectado

| Script | Comportamiento final |
|---|---|
| `scripts/kustomize-build.sh` | Construye y pasa por `kubeconform -strict`: `k8s/overlays/dev` y `k8s/argocd`. Valida `root.yaml` aparte: existe, no aparece en la salida de `k8s/argocd` y su `spec.source.path` es exactamente `$ARGOCD_DIR`. Se eliminan el target `argocd-local` y la aceptación de `${ARGOCD_DIR}-local`. Mensajes de fallo: iguales. Salida en `.rendered/`: `dev.yaml`, `argocd.yaml`, `argocd-root.yaml`. |
| `scripts/policies.sh` / `policy-check.sh` | Sin cambios de lógica: recorren lo que haya en `.rendered/`. Sus reglas (sin `:latest`, sin tag, sin `Secret` en claro, `targetRevision: main`, `repoURL`, finalizer) se mantienen. |
| `scripts/tests/policy-check.test.sh` | Sin cambios: usa fixtures propios; debe seguir detectando los 7 casos malos y aceptar los 2 buenos. |
| `scripts/check-layout.sh` | Sin cambios: exige ≥ 1 entorno raíz completo en `terraform/envs/*/`. |
| `scripts/test.sh` | Sin cambios: valida cada entorno en `terraform/envs/*/` (ahora `dev`). |
| `scripts/smoke.sh` | Solo comentarios: ya no menciona `staging`/`prod`; la espera sigue siendo `shoplab-root` Synced/Healthy y `shoplab-dev` creada. |
| `scripts/lib.sh` | `ENV_NAME="${ENV:-dev}"` (ver `terraform-root-dev.md`). |

## Criterios de aceptación del contrato

1. Con el cambio, `make check` pasa completo en local.
2. La CI de la PR de LAB-101 pasa: `ci-gate` y `pr-title` en verde, y el job `kustomize` informa
   exactamente dos construcciones (`k8s/overlays/dev` y `k8s/argocd`) más `argocd-root.yaml`.
3. Regresión intencional (no se commitea): poner `image: x:latest` en el overlay `dev` hace fallar
   `make policies`, igual que antes (FR-004, escenario 2 de la historia 2).
4. El job `kustomize` no falla por un overlay o directorio inexistente (`falta el overlay '…'`).
5. El flujo de LAB-81 (`deploy-dev.sh` + `policy-check.sh`) sigue pasando contra el overlay `dev`.
