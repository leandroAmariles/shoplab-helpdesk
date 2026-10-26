# Implementation Plan: Consolidar shoplab-infra en un solo ambiente dev

**Branch**: `chore/LAB-101-single-dev-environment` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-101-single-dev-environment/spec.md`

## Summary

`shoplab-infra` define hoy tres ambientes (`dev`, `staging`, `prod`) pero el clúster local solo aloja
`dev`: un overlay extra (`k8s/argocd-local`) oculta las Applications de `staging` y `prod` y el entorno
raíz de Terraform se llama `local`. La historia deja un único ambiente `dev`:

1. **Manifiestos**: se eliminan `k8s/overlays/staging`, `k8s/overlays/prod`, las Applications de
   `staging`/`prod` y el overlay `k8s/argocd-local`; la Application raíz pasa a sincronizar `k8s/argocd`
   directamente (que lista solo `application-dev.yaml`).
2. **Pipeline**: `OVERLAYS=dev` en `versions.env` y `scripts/kustomize-build.sh` deja de construir
   `argocd-local`. Los jobs y el gate `ci-gate` conservan sus nombres, así que la protección de `main`
   no cambia.
3. **Terraform**: `terraform/envs/local` → `terraform/envs/dev` (`git mv`), `ENV` por defecto `dev`,
   se elimina `var.app_environments` y `module.app_secrets` (con `environment = dev` colisionaría con
   `module.secrets`, ver [research.md](research.md) D3).
4. **Documentación**: README y `docs/` de `shoplab-infra`, README y plantilla de PR de
   `shoplab-helpdesk`, y un ADR-0008 que registra la decisión y cómo añadir otro ambiente; ADR-0006 y
   ADR-0007 se marcan como parcialmente reemplazados.
5. **Validación en vivo**: `make check` y un `make up` desde cero hasta Synced/Healthy, registrados en
   `specs/LAB-101-single-dev-environment/validation.md`.

El overlay `dev` no cambia: su salida de `kustomize build` debe ser idéntica antes y después (FR-003).

## Technical Context

**Language/Version**: Terraform 1.15.8 (HCL), Kustomize 5.8.2 (YAML), bash (scripts), GitHub Actions
(YAML). Sin código de aplicación (Java/Python/TS) afectado.

**Primary Dependencies**: Argo CD (chart 10.11.1), Floci 2.3.0 (emulador de AWS), kubeconform 0.8.0,
yq 4.53.6, TFLint 0.65.0, Trivy 0.72.0. Todas fijadas en `versions.env`; ninguna cambia.

**Storage**: Estado de Terraform en un bucket S3 de Floci (`shoplab-tfstate-<ambiente>`), efímero
(vive en el contenedor del emulador). Sin base de datos de la aplicación afectada.

**Testing**: `make check` (fmt, validate, lint, pins, `terraform test` por módulo, kustomize+kubeconform,
políticas, pruebas de políticas, Trivy), `make smoke` (apply completo contra Floci y espera a Argo CD)
y la validación manual en vivo con `make up`.

**Target Platform**: Máquina local del desarrollador (Docker + Floci + k3s) y runners `ubuntu-latest` de
GitHub Actions. Repos afectados: `shoplab-infra` (cambio principal) y `shoplab-helpdesk` (ADR, docs y
spec).

**Project Type**: Infraestructura como código / GitOps (repo separado de la aplicación, ADR-0006).

**Performance Goals**: N/A. Efecto colateral esperado: menos trabajo del pipeline (de 4 construcciones
de kustomize a 2: `dev` y `argocd`).

**Constraints**: El nodo k3s de Floci tiene ~2 CPU: un solo ambiente cabe. La protección de `main` exige
`ci-gate` y `pr-title`. El contrato con LAB-81 (ruta `k8s/overlays/dev/kustomization.yaml`, escritura de
`images:`) no debe cambiar. Las políticas exigen `targetRevision: main`, `repoURL` real y finalizer en
las Applications.

**Scale/Scope**: ~35 archivos eliminados o movidos, ~20 editados en `shoplab-infra`; 1 ADR nuevo y ~5
archivos editados en `shoplab-helpdesk`. Un único clúster local.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Evaluación |
|---|---|
| I. Monolito modular primero | N/A: no toca la aplicación. |
| II. Hexagonal y pureza del dominio | N/A: no hay código de dominio. |
| III. Identificadores en inglés | PASS: los identificadores de HCL/shell (`dev`, `app_secrets`, `ENV_NAME`) ya están en inglés; la documentación puede estar en español. |
| IV. Test-first con umbral de cobertura | PASS: no hay código con cobertura JaCoCo; las pruebas existentes (`terraform test`, políticas, `make check`) se actualizan junto al cambio y se añade una verificación de que no quedan referencias a ambientes eliminados. |
| V. RAG evaluado | N/A (F2+). |
| VI. Observabilidad y manejo seguro | PASS: no se commitean secretos; `secrets.auto.tfvars` sigue ignorado por git y solo se mueve de carpeta en local. |
| VII. Simplicidad y decisiones documentadas | PASS: es una reducción (YAGNI) y se registra como ADR-0008; no se implementa "cómo añadir otro ambiente", solo se documenta. |
| Restricciones: infraestructura | PASS: el arranque local sigue siendo un comando (`make up`); Kubernetes es F0 (LAB-79/80/98). |
| Flujo: rama, historia, PR | PASS: rama `chore/LAB-101-single-dev-environment` = directorio de la spec con prefijo de tipo; título de PR `chore(infra): [LAB-101] …`; plantilla de PR con ADR/docs actualizados. |

Sin violaciones; **Complexity Tracking** queda vacío. Re-evaluación posterior al diseño: sin cambios
(el diseño solo elimina elementos y renombra un directorio).

## Project Structure

### Documentation (this feature)

```text
specs/LAB-101-single-dev-environment/
├── plan.md                         # Este archivo
├── research.md                     # Fase 0: decisiones y alternativas
├── data-model.md                   # Fase 1: entidades antes/después
├── quickstart.md                   # Fase 1: guía de validación
├── contracts/
│   ├── infra-layout.md             # Árbol final de k8s/ y qué se elimina
│   ├── terraform-root-dev.md       # Nombres, variables y estado del entorno raíz dev
│   └── pipeline-checks.md          # Qué construye y exige el pipeline tras el cambio
├── checklists/requirements.md
├── tasks.md                        # Fase 2 (/speckit-tasks, no lo crea este comando)
└── validation.md                   # Resultado de la validación en vivo (lo crea /speckit-implement)
```

### Source Code (repositorios afectados)

```text
shoplab-infra/                                  # repo principal del cambio
├── k8s/
│   ├── base/                                   # sin cambios
│   ├── overlays/
│   │   ├── dev/                                # sin cambios (salida de build idéntica)
│   │   ├── staging/                            # ELIMINAR
│   │   └── prod/                               # ELIMINAR
│   ├── argocd/
│   │   ├── application-dev.yaml                # sin cambios
│   │   ├── application-staging.yaml            # ELIMINAR
│   │   ├── application-prod.yaml               # ELIMINAR
│   │   ├── kustomization.yaml                  # solo application-dev.yaml
│   │   └── root.yaml                           # path: k8s/argocd; comentario actualizado
│   └── argocd-local/                           # ELIMINAR (existía solo para ocultar staging/prod)
├── terraform/envs/
│   └── local/ → dev/                           # git mv; backend, variables, main, tests, bootstrap
├── scripts/
│   ├── lib.sh                                  # ENV por defecto: dev
│   ├── kustomize-build.sh                      # sin target argocd-local ni su validación de root
│   ├── smoke.sh                                # comentarios sin staging/prod
│   └── …                                       # referencias a "local" que sean el nombre del entorno
├── versions.env                                # OVERLAYS=dev
├── .gitignore                                  # rutas terraform/envs/local → dev si están explícitas
├── README.md, docs/{gitops,kustomize,terraform-local}.md
└── .github/workflows/ci.yml                    # sin cambios de nombres de job (solo comentarios si aplica)

shoplab-helpdesk/                               # repo de la aplicación
├── docs/adr/0008-un-solo-ambiente-dev.md       # NUEVO
├── docs/adr/{README,0006-…,0007-…}.md          # índice + notas de reemplazo parcial
├── README.md                                   # Definition of Done: base + overlay dev
├── .github/pull_request_template.md            # idem
└── specs/LAB-101-single-dev-environment/       # esta carpeta
```

**Structure Decision**: Cambio de dos repositorios, como las historias LAB-79 a LAB-99: la spec, el ADR
y la documentación de la aplicación viven en `shoplab-helpdesk`; los manifiestos, Terraform y scripts en
`shoplab-infra`, en una rama `chore/LAB-101-single-dev-environment` propia con su PR. Orden de fusión:
primero `shoplab-infra` (la validación en vivo corre sobre él), luego `shoplab-helpdesk` con los
resultados. Las especificaciones históricas (LAB-79/80/81/98/99) no se tocan.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
