# Validación: consolidar shoplab-infra en un solo ambiente dev

**Fecha**: 2026-10-26 | **PR**: [shoplab-infra#12](https://github.com/leandroAmariles/shoplab-infra/pull/12) (`529f4c3`) y
[shoplab-helpdesk#59](https://github.com/leandroAmariles/shoplab-helpdesk/pull/59) (`cfe9e1c`) | **Estado**: validada; lista
para cerrar la historia.

## Entorno de la verificación local

Herramientas presentes: `kustomize` 5.8.1 (el repo pide 5.8.2; se usó `ALLOW_TOOL_DRIFT=1`), `terraform` 1.15.8, `tflint`,
`docker`, `kubectl`, bash. **Faltan** `make`, `kubeconform` y `yq`, de modo que `make check` no se ejecutó en el equipo del
desarrollador: se invocaron los scripts directamente y el resto lo cubrió la CI (ver más abajo). No se instalaron binarios
de terceros.

## Verificación local previa a los PR

| Comprobación | Tarea | Resultado |
|---|---|---|
| No había entorno local anterior que destruir | T004 | OK |
| `kustomize build k8s/overlays/dev` antes y después del cambio | T008 | Idéntico (diferencia cero, 747 líneas) |
| `kustomize build k8s/argocd` | T009 | Una sola `Application` (`shoplab-dev`); no incluye `shoplab-root` |
| Residuos de `staging`, `prod`, `argocd-local` en `k8s/` | T010 | Ninguno |
| `.github/` sin menciones a `staging`/`prod`; contextos `ci-gate` y `pr-title` sin cambios | T016 | OK |
| `bash -n` de `kustomize-build.sh` y `smoke.sh` | T012–T013 | OK |
| `terraform fmt -check`, estructura y pines, `validate` (módulos, `envs/dev`, bootstrap) | T024 | OK |
| `terraform test` de módulos (eks 5, platform 13, secrets 4, state 2) | T024 | 24 pasan, 0 fallan |
| TFLint recursivo | T024 | OK |
| Búsqueda de `envs/local`, `shoplab-local`, `app_environments`, `"local"` en Terraform, scripts, Makefile, `.github/` | T025 | Ninguno |
| Búsqueda de residuos en ambos repositorios | T041 | Solo las secciones de migración de `shoplab-infra` (FR-014) y ADR-0006/0007/0008 |

## CI

| Ejecución | Resultado |
|---|---|
| PR `shoplab-infra#12`, `9062e0f` y `20f7538`: `changes`, `static`, `kustomize`, `trivy`, `plan`, `smoke`, `ci-gate`, `pr-title` | Todo en verde |
| `main` `d9ea98a` (antes del cambio) | Verde |
| `main` `529f4c3` (merge de LAB-101) | **`smoke` falló** y con él `ci-gate`; el resto en verde |
| `main` `2163d0e` (despliegue continuo posterior, ya con LAB-101) | Todo en verde, incluido `smoke` y `ci-gate` |
| `shoplab-helpdesk` `cfe9e1c`: `CI`, `Images`, `Release Please` | Verde |

**Fallo de `smoke` en `529f4c3`**: `terraform apply` terminó con `ResourceInUseException: Cluster already exists:
shoplab-dev` al crear el clúster; el reintento desde cero falló igual porque Floci conservó el clúster del primer
intento. El log de Floci muestra el clúster `ACTIVE`: el 409 es un reintento del cliente ante una creación lenta del
k3s (en ese intento Floci tuvo que descargar `rancher/k3s`). **No es atribuible al cambio**: el mismo código pasó `smoke` dos
veces en el PR y de nuevo en `2163d0e`, y ya había ocurrido un fallo equivalente en `main` (`adc853f`, antes de LAB-101) que
se resolvió con la ejecución siguiente. Queda como riesgo de intermitencia del `smoke` en CI, fuera del alcance de esta
historia.

## Validación en vivo (T028–T030)

`bash scripts/up.sh` desde cero (sin `make`), sobre `main` con LAB-101 fusionado y la raíz apuntando a `k8s/argocd`:

| Comprobación | Resultado |
|---|---|
| `up.sh` | Terminó con código 0 |
| `kubectl -n argocd get applications` | `shoplab-root` Synced/Healthy y `shoplab-dev` Synced/**Progressing**; ninguna otra Application |
| Raíz | `spec.source.path: k8s/argocd`, `targetRevision: main` |
| Namespaces de aplicación | Solo `shoplab-dev` (más `demo`, el `ExternalSecret` de demostración) |
| Secretos en Floci | Solo `/shoplab/dev/llm-api-key` y `/shoplab/dev/postgres-password` |
| Condiciones de `shoplab-dev` | Ninguna (sin `ComparisonError`) |
| Pods de `shoplab-dev` | backend, frontend e ingest `Running`; postgres y ollama `Pending` |
| `down.sh` y `verify-clean.sh` | Código 0; sin contenedores de Floci ni de `shoplab-dev` |

`shoplab-dev` queda `Progressing` por la limitación ya conocida de `docs/kustomize.md`: el k3s de Floci no trae
`StorageClass`, así que los PVC `data-postgres-0` y `models-ollama-0` quedan `Pending`. Es idéntico al comportamiento previo
al cambio y no es un efecto de LAB-101; lo relevante es que `dev` está `Synced` sin errores.

## Despliegue continuo (T046, SC-005)

Al fusionar `shoplab-helpdesk#59` (`cfe9e1c`), `images.yml` generó en `shoplab-infra` el commit
`2163d0e chore(dev): [LAB-81] despliega cfe9e1c a dev`. El flujo de LAB-81 sigue funcionando con el overlay `dev`
consolidado.

## Cobertura de los criterios de aceptación de la historia

| Criterio | Evidencia |
|---|---|
| Quedan `base/` + overlay `dev` | `ls k8s/overlays` → `dev`; T008–T010 |
| Sin Applications de `staging`/`prod` | `kustomize build k8s/argocd` → solo `shoplab-dev`; en vivo, solo 2 Applications |
| El pipeline solo construye y valida `dev` | `OVERLAYS=dev`; job `kustomize` en verde en el PR y en `2163d0e` |
| Terraform deja un único entorno raíz `envs/dev` | `ls terraform/envs` → `dev`; `validate` y `terraform test` en verde; `up.sh` aplica `envs/dev` |
| README y `docs/` actualizados; el ADR registra la decisión y cómo añadir ambientes | ADR-0008, índice, notas de estado en ADR-0006/0007, README y `docs/` de ambos repos |
| `terraform apply` y Argo CD llevan dev a Synced y Healthy | `shoplab-root` Healthy y `shoplab-dev` **Synced** en vivo; **`Progressing`, no `Healthy`**, por los PVC sin `StorageClass` (limitación previa del entorno local); `smoke` en CI verde en el PR y en `2163d0e` |

**Salvedad para el cierre**: el último criterio pide "Synced y Healthy". En el entorno local `shoplab-dev` llega a Synced
pero no a Healthy por la ausencia de `StorageClass` en el k3s de Floci, un problema anterior a esta historia y documentado.
La CI ya trata `Healthy` de `shoplab-dev` como no exigible por la misma razón (`ARGOCD_PRESENT_APPS`).

## Pendiente fuera de esta historia

- Intermitencia del `smoke` (`Cluster already exists`) si la creación del clúster en Floci es lenta.
- `StorageClass` en el k3s de Floci para que `dev` llegue a Healthy en local.
- Instalar `make`, `kubeconform` y `yq` en el equipo de desarrollo para ejecutar `make check` completo en local.
