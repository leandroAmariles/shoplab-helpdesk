# Research: Consolidar shoplab-infra en un solo ambiente dev

Cada decisión parte de lo que hay hoy en `shoplab-infra` (rama `main`, commit `bb1d214`). No quedaban
marcadores `NEEDS CLARIFICATION` en el contexto técnico; estas son las decisiones de diseño.

## D1 — La Application raíz sincroniza `k8s/argocd` y se elimina `k8s/argocd-local`

- **Decision**: `root.yaml` pasa a `path: k8s/argocd`; `k8s/argocd/kustomization.yaml` lista solo
  `application-dev.yaml`; se borra `k8s/argocd-local/`.
- **Rationale**: `argocd-local` solo existía para quitar `shoplab-staging` y `shoplab-prod` con
  `$patch: delete`. Sin esas Applications no hay nada que ocultar, y una capa intermedia que no hace
  nada es complejidad pura (VII). `kustomize-build.sh` ya acepta `ARGOCD_DIR` como ruta válida de la
  raíz.
- **Alternatives considered**: (a) dejar `argocd-local` como alias vacío: confunde y obliga a
  mantener una validación inútil. (b) Fusionar la raíz dentro del kustomization: está prohibido, la
  raíz no se gestiona a sí misma (bucle de autogestión con `prune`).

## D2 — El entorno raíz de Terraform se renombra a `envs/dev` y el valor por defecto de `environment` es `dev`

- **Decision**: `git mv terraform/envs/local terraform/envs/dev`; `variable "environment"` y el de
  `bootstrap/variables.tf` pasan a `dev`; `backend.tf` usa el bucket `shoplab-tfstate-dev`;
  `scripts/lib.sh` usa `ENV_NAME="${ENV:-dev}"`.
- **Rationale**: Es el criterio de aceptación literal y alinea el nombre del entorno con el del
  ambiente que levanta. El nombre del clúster pasa de `shoplab-local` a `shoplab-dev`, y el prefijo de
  secretos de `/shoplab/local/` a `/shoplab/dev/`, que es justo el que ya leen los `ExternalSecret`
  del overlay `dev`.
- **Alternatives considered**: (a) mover la carpeta pero conservar `environment = "local"`: deja dos
  nombres para lo mismo y mantiene viva la duplicación de secretos de D3. (b) No renombrar y dar el
  criterio por cumplido porque ya hay un único entorno: incumple "queda `envs/dev`".

## D3 — Se elimina `module.app_secrets` y `var.app_environments`; `module.secrets` queda como única fuente

- **Decision**: borrar el `for_each` de `module.app_secrets` y la variable `app_environments` (con su
  validación `["dev","staging","prod"]`).
- **Rationale**: hoy `module.secrets` crea `/shoplab/local/*` y `module.app_secrets["dev"]` crea
  `/shoplab/dev/*`. Con `environment = "dev"` ambos crearían los mismos nombres y el `apply` fallaría
  con "ya existe". Una sola instancia cubre ambos usos. La variable ya no tiene sentido: el único
  ambiente es el del entorno raíz (FR-007).
- **Alternatives considered**: conservar `app_environments = ["dev"]` y renombrar: sigue ofreciendo
  ambientes que no existen y mantiene el choque. Rechazado.
- **Riesgo a verificar**: la prueba `terraform test` del módulo `secrets` y la de integración
  (`apply.tftest.hcl`, ambiente `itest`) no deben depender de `app_secrets`.

## D4 — El estado de Terraform de Floci es desechable: no se migra, se recrea

- **Decision**: el procedimiento soportado para quien tenga un clúster local previo es
  `make down` con la configuración **anterior** (o `docker compose down -v` si no responde) y luego
  `make up` con la nueva. No se escribe un script de migración de estado.
- **Rationale**: el bucket de estado vive dentro del contenedor de Floci, que ya se destruye en cada
  ciclo (`down.sh`, `smoke.sh`). El renombre cambia el bucket (`-local` → `-dev`) y la clave del estado
  (`local/…` → `dev/…`), así que el estado antiguo queda huérfano si no se destruye antes. Migrarlo (`terraform state mv`
  entre backends) costaría más que recrear un entorno de laboratorio de minutos.
- **Consecuencia sobre la spec**: FR-009 pide aplicar sobre un clúster existente "sin recrear sus
  recursos con estado". Con el renombre del clúster (`shoplab-local` → `shoplab-dev`) eso no es
  alcanzable. **Propuesta de ajuste (no aplicada a `spec.md`)**: FR-009/SC-004 pasan a exigir "recrear
  desde cero con el procedimiento documentado y llegar a Synced/Healthy"; el dato persistente del
  laboratorio (Postgres/Ollama de `dev`) se pierde al recrear, igual que hoy con `make down`.
  **Aplicada a `spec.md` el 2026-10-26** (opción A elegida; la alternativa D2(a), conservar
  `environment = "local"`, queda descartada).
- **Alternatives considered**: migrar estado con `terraform state mv`/`-migrate-state`: sobreingeniería
  para un emulador efímero. Rechazado.

## D5 — El pipeline conserva jobs y nombres; solo cambia lo que construye

- **Decision**: `versions.env` → `OVERLAYS=dev`; `kustomize-build.sh` construye `k8s/overlays/dev` y
  `k8s/argocd`, valida `root.yaml` y exige que su `path` sea `$ARGOCD_DIR` (se elimina la rama
  `${ARGOCD_DIR}-local`). `ci.yml` no cambia los nombres de job (`changes`, `static`, `kustomize`,
  `trivy`, `plan`, `smoke`, `ci-gate`).
- **Rationale**: la protección de `main` exige `ci-gate` y `pr-title`; mientras `ci-gate` siga existiendo
  y agregue los mismos `needs`, los PR se pueden fusionar. `ARGOCD_PRESENT_APPS=shoplab-dev` y
  `ARGOCD_READY_APPS=shoplab-root` ya describen un solo ambiente.
- **Alternatives considered**: reducir `ci.yml` a menos jobs: cambiaría los contextos de estado sin
  necesidad. Rechazado.

## D6 — Las pruebas con nombre `local` se actualizan por coherencia

- **Decision**: pruebas de módulos (`secrets`, `state`, `eks`) y comentarios que usan `local`/
  `shoplab-local` como valor de ejemplo pasan a `dev`/`shoplab-dev`; los que usan `itest` no cambian.
- **Rationale**: SC-001 exige que una búsqueda de ambientes eliminados no devuelva nada; y mantener
  `local` en las pruebas contradice el nombre real. Son datos de prueba, no lógica.
- **Alternatives considered**: dejarlas: inocuas funcionalmente pero dejan el rastro que la historia quiere borrar.

## D7 — Documentación: ADR nuevo + notas en ADRs vigentes, sin reescribir historia

- **Decision**: ADR-0008 `un-solo-ambiente-dev` (decisión, motivo, consecuencias, cómo añadir otro
  ambiente); ADR-0006 y ADR-0007 reciben una nota de "reemplazado parcialmente por ADR-0008" en su
  estado/consecuencias, sin editar su cuerpo. Se editan `README.md` y `docs/` de `shoplab-infra`
  (`gitops.md`, `kustomize.md`, `terraform-local.md`), y en `shoplab-helpdesk` el README (Definition of
  Done, línea 46) y la plantilla de PR (línea 19).
- **Rationale**: los ADRs son registros históricos (FR-013). La documentación operativa (README,
  guías) sí debe decir la verdad actual (FR-011).
- **Alternatives considered**: editar ADR-0006 en sitio: pierde la trazabilidad de por qué existieron
  tres ambientes. Rechazado.

## D8 — "Cómo añadir otro ambiente" se documenta, no se implementa

- **Decision**: el ADR-0008 lista los pasos mínimos: (1) `k8s/overlays/<env>/` sobre `base/`, (2)
  `k8s/argocd/application-<env>.yaml` y añadirla al kustomization, (3) añadir `<env>` a `OVERLAYS` en
  `versions.env`, (4) un `terraform/envs/<env>/` si necesita su propio clúster/estado y (5) las claves
  de secretos `/shoplab/<env>/…`. No hay generador ni plantilla.
- **Rationale**: YAGNI (VII); el criterio pide el procedimiento, no la capacidad.
- **Alternatives considered**: dejar `staging`/`prod` como plantillas comentadas: reintroduce
  mantenimiento muerto. Rechazado.

## D9 — La validación en vivo recrea el clúster desde cero

- **Decision**: la prueba es `make check` + `make up` desde cero hasta que `shoplab-root` y
  `shoplab-dev` estén Synced y Healthy, más un `make down`; el resultado se registra en
  `validation.md`. `make smoke` ya la ejecuta en CI con la restricción conocida de que `shoplab-dev`
  solo debe estar creada (no Healthy) en un runner.
- **Rationale**: es lo que hizo LAB-79 (ver su registro de validación) y cubre FR-008/FR-015.
- **Alternatives considered**: validar solo en CI: no demuestra Synced/Healthy de `dev` (ver nota de
  `smoke.sh` sobre `StorageClass` en el k3s de Floci).
