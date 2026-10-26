# Data Model: Despliegue continuo a dev

No hay base de datos. Las entidades son artefactos de git y de CI.

## Despliegue a dev (ejecución del job)

| Campo | Origen | Regla |
|---|---|---|
| `app_sha` | `github.sha` | 40 hex; es el tag de las imágenes |
| `run_url` | `github.server_url/repository/actions/runs/run_id` | enlace a la ejecución de CI |
| `services` | matriz de `publish`: `backend`, `frontend`, `ingest` | todos o ninguno |
| `registry_prefix` | `ghcr.io/leandroamariles` | de `IMAGE_NAMESPACE` de `images.yml` |
| `infra_repo` | `leandroAmariles/shoplab-infra` | único repositorio al que escribe |
| `overlay` | `k8s/overlays/dev` | único directorio modificado |

**Estados**: `skipped` (no es push a `main` o falló `publish`) → `validated` (tags existen, servicios declarados) →
`edited` → `checked` (build + política) → `no-change` | `pushed` | `failed`.

**Transiciones inválidas**: `failed` nunca deja cambios en `shoplab-infra`; `no-change` no crea commit.

## Entrada de imagen del overlay `dev`

| Campo | Antes (LAB-80) | Después |
|---|---|---|
| `name` | `ghcr.io/leandroamariles/<svc>` | igual |
| `digest` | `sha256:…` | ausente |
| `newTag` | ausente | `<app_sha>` (40 hex) |

**Validaciones**: `newTag` coincide con `app_sha`; no existe `digest`; ningún valor es `latest`.

## Commit de despliegue

Ver [contracts/deploy-commit-contract.md](contracts/deploy-commit-contract.md). Es la unidad de auditoría y de
rollback: uno por SHA desplegado.

## Credencial (GitHub App)

| Atributo | Valor |
|---|---|
| Instalación | solo `shoplab-infra` |
| Permisos | `contents: write`, `metadata: read` |
| Token | de instalación, caduca en 1 h, enmascarado en logs |
| Almacén | `Environment` `dev`: `DEPLOY_APP_ID`, `DEPLOY_APP_PRIVATE_KEY` |
| Rotación | regenerar la clave privada y actualizar el secreto; revocar = desinstalar la App |
