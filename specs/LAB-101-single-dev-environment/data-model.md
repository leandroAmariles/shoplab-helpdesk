# Data Model: Consolidar shoplab-infra en un solo ambiente dev

No hay datos de aplicación. El "modelo" son las entidades de configuración de `shoplab-infra` que
cambian; cada fila indica su estado antes y después.

## Ambiente

| Atributo | Antes | Después |
|---|---|---|
| Ambientes definidos | `dev`, `staging`, `prod` | `dev` |
| Ambiente que corre en local | `dev` (los otros se ocultaban) | `dev` |
| Namespace | `shoplab-dev` / `shoplab-staging` / `shoplab-prod` | `shoplab-dev` |
| Prefijo de secretos | `/shoplab/{dev,staging,prod}/…` (y `/shoplab/local/…`) | `/shoplab/dev/…` |

Regla: un ambiente existe si y solo si tiene overlay, Application de Argo CD y claves de secretos.
`dev` es el único que cumple las tres.

## Overlay (`k8s/overlays/<ambiente>`)

| Overlay | Antes | Después | Notas |
|---|---|---|---|
| `dev` | existe | existe, **sin cambios** | `kustomize build` idéntico (FR-003) |
| `staging` | existe | **eliminado** | host de ejemplo, claves `/shoplab/staging/…`, recursos y HPA propios |
| `prod` | existe | **eliminado** | idem para `prod` |

Relación: cada overlay extiende `k8s/base/` y lo consume una sola Application.

## Application de Argo CD (`k8s/argocd/`)

| Application | Antes | Después |
|---|---|---|
| `shoplab-root` (`root.yaml`) | `path: k8s/argocd-local` | `path: k8s/argocd` |
| `shoplab-dev` | gestionada por la raíz | sin cambios |
| `shoplab-staging` | definida; retirada en local por `argocd-local` | **eliminada** |
| `shoplab-prod` | idem | **eliminada** |
| `k8s/argocd-local` (kustomization) | existe | **eliminado** |

Invariantes que validan las políticas (sin cambios): `targetRevision: main`, `repoURL` real, finalizer
`resources-finalizer.argocd.argoproj.io`, y la raíz no aparece en la salida de `k8s/argocd`.

Transición de estado de la raíz en un clúster ya creado: `path` cambia solo con `terraform apply`
(la raíz no se autogestiona). Entre el merge a `main` y ese apply, una raíz antigua apuntaría a
`k8s/argocd-local`, que ya no existe: `ComparisonError` hasta recrear (ver research D4).

## Entorno raíz de Terraform

| Atributo | Antes (`envs/local`) | Después (`envs/dev`) |
|---|---|---|
| Directorio | `terraform/envs/local` | `terraform/envs/dev` |
| `var.environment` (defecto) | `local` | `dev` |
| Clúster | `shoplab-local` | `shoplab-dev` |
| Bucket de estado | `shoplab-tfstate-local` | `shoplab-tfstate-dev` |
| Clave de estado | `local/terraform.tfstate` | `dev/terraform.tfstate` |
| Estado del bootstrap | `terraform.local.tfstate` | `terraform.dev.tfstate` |
| `module.secrets` | prefijo `/shoplab/local/` | prefijo `/shoplab/dev/` |
| `module.app_secrets` | `for_each` sobre `app_environments` | **eliminado** (D3) |
| `var.app_environments` | `["dev"]`, valida `dev/staging/prod` | **eliminada** |
| Demo ExternalSecret `remoteKey` | `/shoplab/local/postgres-password` | `/shoplab/dev/postgres-password` |

## Configuración del pipeline (`versions.env`)

| Clave | Antes | Después |
|---|---|---|
| `OVERLAYS` | `dev,staging,prod` | `dev` |
| `ARGOCD_DIR` | `k8s/argocd` | sin cambios |
| `ARGOCD_READY_APPS` | `shoplab-root` | sin cambios |
| `ARGOCD_PRESENT_APPS` | `shoplab-dev` | sin cambios |

## ADR

| Registro | Estado tras el cambio |
|---|---|
| ADR-0006 GitOps pull y repo de infra separado | Aceptado; nota "reemplazado parcialmente por ADR-0008" (tres ambientes) |
| ADR-0007 Despliegue continuo a dev | Aceptado; nota sobre el alcance de ambientes |
| ADR-0008 Un solo ambiente `dev` | **Nuevo**, estado Aceptado; incluye cómo añadir otro ambiente |
