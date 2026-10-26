# Contrato: entorno raíz de Terraform `envs/dev`

Qué debe cumplir `terraform/envs/dev` tras renombrar `envs/local` (FR-006, FR-007, FR-008).

## Movimiento

```bash
git mv terraform/envs/local terraform/envs/dev
```

`git mv` mueve solo lo versionado. Lo ignorado por git (`secrets.auto.tfvars`, `kubeconfig.yaml`,
`.terraform/`, `bootstrap/terraform.*.tfstate`) **no** se mueve: `secrets.auto.tfvars` se recrea desde
`secrets.auto.tfvars.example` (o se mueve a mano) y lo demás se regenera con `make up`.

## Nombres resultantes

| Elemento | Valor |
|---|---|
| Directorio del entorno | `terraform/envs/dev` |
| `var.environment` (en el entorno y en `bootstrap/`) | `dev` |
| `ENV_NAME` en `scripts/lib.sh` | `${ENV:-dev}` |
| Clúster (`module.eks.cluster_name`) | `shoplab-dev` |
| Contenedor del plano de control (modo `kind`) | `shoplab-dev-control-plane` |
| Bucket de estado | `shoplab-tfstate-dev` (`backend.tf` y `bootstrap/main.tf`) |
| Clave de estado | `dev/terraform.tfstate` (`scripts/init-env.sh`) |
| Estado del bootstrap | `bootstrap/terraform.dev.tfstate` |
| Secretos | `/shoplab/dev/<nombre>` |
| `local.argocd_root` | `yamldecode(file("../../../k8s/argocd/root.yaml"))` (ruta relativa igual) |

## Variables y recursos

| Elemento | Cambio |
|---|---|
| `variable "app_environments"` | **eliminada** (con su validación `dev/staging/prod`) |
| `module "app_secrets"` | **eliminado**; colisionaría con `module.secrets` (mismo prefijo `/shoplab/dev/`) |
| `module "secrets"` | sin cambios de código; ahora crea `/shoplab/dev/*`, que lee el overlay `dev` |
| `helm_release.demo_external_secret` | `remoteKey = /shoplab/${var.environment}/postgres-password` sin cambios de código |

No se añade ninguna variable nueva ni valor por defecto de otro ambiente.

## Pruebas que acompañan al renombre

| Prueba | Ajuste |
|---|---|
| `terraform/envs/dev/tests/apply.tftest.hcl` | comentario: el ambiente `itest` evita chocar con `shoplab-dev`; las aserciones no cambian |
| `terraform/modules/secrets/tests/secrets.tftest.hcl` | `environment = "dev"` y nombres `/shoplab/dev/…` |
| `terraform/modules/state/tests/state.tftest.hcl` | bucket `shoplab-tfstate-dev` y tag `Environment = "dev"` |
| `terraform/modules/eks/tests/eks.tftest.hcl` | `cluster_name = "shoplab-dev"` y rol `shoplab-dev-eks` |

## Criterios de aceptación del contrato

1. `ls terraform/envs` devuelve solo `dev`.
2. `scripts/check-layout.sh` y `scripts/test.sh` (recorren `terraform/envs/*/`) pasan con un único entorno.
3. `terraform -chdir=terraform/envs/dev validate` pasa en el entorno y en `bootstrap/`.
4. `make up` desde cero deja `shoplab-root` y `shoplab-dev` Synced/Healthy sin pasos manuales extra.
5. No queda ninguna referencia a `terraform/envs/local` en scripts, `.gitignore`, comentarios ni docs
   (incluida la ruta citada en el comentario de `k8s/argocd/root.yaml`).
