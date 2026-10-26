# Implementation Plan: Despliegue continuo a dev con kustomize edit set image

**Branch**: `feature/LAB-81-continuous-deploy-dev` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-81-continuous-deploy-dev/spec.md`

## Summary

Cerrar el ciclo de entrega continua: tras publicar y firmar las tres imágenes de un merge a `main`
(`images.yml`, LAB-78), un job `deploy` clona `shoplab-infra`, ejecuta `kustomize edit set image` sobre
`k8s/overlays/dev` con el tag del SHA completo, valida el resultado con las mismas herramientas del CI de
infraestructura y empuja un commit que enlaza el SHA y la ejecución de CI. Argo CD (LAB-79) ya sincroniza `dev`
desde `main`, así que el job nunca toca el clúster. Decisiones clave (ver [research.md](research.md)):

1. **Identidad**: una GitHub App instalada solo en `shoplab-infra` con permiso `contents: write`; el job obtiene un
   token de instalación efímero de 1 h. La App se añade como **actor de bypass** del ruleset de `main` de
   `shoplab-infra` (hoy exige PR y no tiene bypass), de modo que el commit entra directo sin pasos manuales.
2. **Seguridad compensatoria**: antes de empujar, el job construye el overlay, valida el esquema y ejecuta
   `policy-check.sh` de `shoplab-infra`; no se empuja nada que el CI de infra rechazaría.
3. **Orden**: el job usa un grupo de concurrencia propio sin cancelar, sobre el grupo ya serializado de
   `images.yml`; ante conflicto, `pull --rebase` y hasta 3 reintentos.
4. **Lógica en un script** (`.github/scripts/deploy-dev.sh`) probado con un repositorio git local de fixture, no
   incrustada en el YAML.
5. **Cambio puntual en `shoplab-infra`**: ruleset con el bypass y limpieza del comentario obsoleto de LAB-80 del
   overlay `dev` (hoy fija por digest; el primer despliegue pasa a `newTag`).

## Technical Context

**Language/Version**: GitHub Actions YAML + Bash; Kustomize 5.8.2 (`versions.env` de `shoplab-infra`, la misma
versión que valida su CI); `yq` 4.x para comprobaciones.

**Primary Dependencies**: `imranismail/setup-kustomize` (impuesta por la historia, fijada por SHA como el resto de
acciones); `actions/create-github-app-token`; `docker buildx imagetools` para comprobar que el tag existe;
`scripts/kustomize-build.sh`, `scripts/policy-check.sh` y kubeconform de `shoplab-infra`; Argo CD (LAB-79).

**Storage**: N/A (el estado es el overlay `dev` en git).

**Testing**: pruebas del script con un remoto git local (`file://`) y un overlay de fixture: éxito, idempotencia,
imagen inexistente, servicio no declarado, conflicto con reintento, token ausente. `actionlint` sobre el workflow.
Validación en vivo con [quickstart.md](quickstart.md).

**Target Platform**: runners `ubuntu-latest`; clúster local Floci/k3s con solo `dev` (ADR-0006).

**Project Type**: pipeline de CI/CD + documentación; sin código de aplicación.

**Performance Goals**: commit de despliegue < 5 min desde la publicación (SC-002); convergencia de `dev` < 10 min
(SC-003, intervalo de sondeo de Argo CD de 3 min).

**Constraints**: sin `kubectl` ni credenciales del clúster; sin `:latest`; token solo para `shoplab-infra`; el job
solo corre en `push` a `main`; no modifica `staging` ni `prod`.

**Scale/Scope**: 1 job nuevo, 1 script y sus pruebas, 1 GitHub App, 1 cambio de ruleset, 1 guía, 1 ADR.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | Cumple: despliega los tres servicios ya definidos; sin servicios nuevos. |
| II. Arquitectura hexagonal | N/A: no se toca código de aplicación. |
| III. Identificadores en inglés | Cumple: job, script y variables en inglés; documentación en español. |
| IV. Test-first con gate de cobertura | Cumple en espíritu: las pruebas del script se escriben antes que el script. JaCoCo no aplica. |
| V. RAG evaluado | N/A (F0). |
| VI. Seguridad | Cumple: token efímero de alcance mínimo, secreto de Environment, enmascarado en logs, no disponible para PR. |
| VII. Simplicidad y decisiones documentadas | Cumple con registro: el bypass del ruleset y la elección App+push directo frente a PR automática se documentan en un ADR nuevo (0007). |
| Infraestructura: "Kubernetes llega en F7" | Misma desviación ya aceptada en LAB-98/79/80. |
| Flujo / commits | Cumple: rama `feature/LAB-81-…`; PR `feat(ci): [LAB-81] …`. Habrá una PR hermana en `shoplab-infra`. |

Gate: **PASA**. Re-evaluado tras el diseño de Fase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-81-continuous-deploy-dev/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── deploy-job-contract.md      # disparo, permisos, entradas/salidas y fallos del job
│   └── deploy-commit-contract.md   # formato del commit y del resumen
├── checklists/requirements.md
└── tasks.md                        # /speckit-tasks (no lo crea este comando)
```

### Source Code (repository root)

```text
shoplab-helpdesk/
├── .github/
│   ├── workflows/images.yml             # + job deploy (needs: publish, solo push a main)
│   └── scripts/
│       ├── deploy-dev.sh                # edita, valida, commitea y empuja
│       └── tests/deploy-dev.test.sh     # remoto git local + fixtures
├── docs/
│   ├── adr/0007-despliegue-continuo-dev-app-con-bypass.md
│   └── despliegue-continuo.md           # flujo, versión desplegada, rollback, rotación del token
└── specs/LAB-81-continuous-deploy-dev/

shoplab-infra/                           # PR hermana
├── .github/rulesets/main-protection.json  # + bypass_actors: la App
├── k8s/overlays/dev/kustomization.yaml    # comentario de LAB-80 actualizado
└── docs/gitops.md                         # enlace a la guía y a la sección de rollback
```

**Structure Decision**: la lógica vive en un script versionado y probado; el workflow solo orquesta (token,
instalación de herramientas, llamada al script, resumen). El `Environment` `dev` del repositorio guarda
`DEPLOY_APP_ID` (variable) y `DEPLOY_APP_PRIVATE_KEY` (secreto).

## Complexity Tracking

| Desviación | Por qué | Alternativa descartada |
|---|---|---|
| Bypass del ruleset de `shoplab-infra` para una App | La PR obligatoria sin bypass impide el commit directo que pide la historia | PR automática con auto-merge: añade la espera de `ci-gate` y la actualización de ramas (`strict`), incumple SC-002 y exige permisos de PR |
