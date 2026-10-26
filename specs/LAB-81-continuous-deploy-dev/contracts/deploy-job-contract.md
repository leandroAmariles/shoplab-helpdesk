# Contrato: job `deploy` de `images.yml`

## Disparo

| Condición | Valor |
|---|---|
| `needs` | `publish` (las tres entradas de la matriz en verde) |
| `if` | `github.event_name == 'push' && github.ref_type == 'branch'` (nunca PR ni tag) |
| `environment` | `dev` |
| `concurrency` | grupo `deploy-dev`, `cancel-in-progress: false` |
| `timeout-minutes` | 10 |

## Permisos

| Ámbito | Permiso |
|---|---|
| `GITHUB_TOKEN` | `contents: read`, `packages: read` |
| Token de la App | `contents: write` en `shoplab-infra` únicamente |

## Pasos

1. Generar el token de instalación (acción fijada por SHA).
2. Instalar Kustomize con `imranismail/setup-kustomize` en la versión de `versions.env` de infra.
3. Iniciar sesión en GHCR (lectura) para comprobar tags.
4. Clonar `shoplab-infra` (`main`) con el token.
5. Ejecutar `.github/scripts/deploy-dev.sh`.
6. Escribir el resumen en `$GITHUB_STEP_SUMMARY`.

## Script `deploy-dev.sh`

**Entradas (entorno)**: `APP_SHA`, `RUN_URL`, `INFRA_DIR`, `REGISTRY_PREFIX`, `SERVICES` (lista separada por
comas), `OVERLAY` (por defecto `k8s/overlays/dev`), `PUSH_RETRIES` (por defecto 3).

| Salida / código | Significado |
|---|---|
| `0` + `result=pushed` | commit creado y empujado |
| `0` + `result=no-change` | el SHA ya estaba fijado |
| `10` | falta una imagen en el registro |
| `11` | un servicio publicado no figura en el overlay |
| `12` | `kustomize build` o política de infra fallaron |
| `13` | no se pudo empujar tras los reintentos |

**Garantías**: nunca invoca `kubectl`; nunca escribe `latest`; solo modifica `OVERLAY/kustomization.yaml`; ante
cualquier código distinto de 0 no deja commits sin empujar.

## Resumen

Siempre se escribe: SHA, servicios y tags, enlace al commit de infra (o el motivo de `no-change`/fallo).
