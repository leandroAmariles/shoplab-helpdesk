# Implementation Plan: Pipeline del repo de infraestructura

**Branch**: `chore/LAB-99-infra-repo-pipeline` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-99-infra-repo-pipeline/spec.md`

## Summary

Añadir al repositorio `shoplab-infra` un pipeline de GitHub Actions (`ci.yml`) que valida cada PR a `main`:
formato, validación, TFLint y `terraform test`; `kustomize build` de `dev`/`staging`/`prod` + kubeconform;
políticas de manifiestos (imágenes sin versión, `repoURL` de plantilla, `targetRevision` distinto de `main`);
Trivy config con SARIF; `terraform plan` contra Floci comentado en el PR; y una prueba de humo
(apply → Argo CD Synced/Healthy → destroy). Un job agregador `ci-gate` (mismo patrón que el repo de la app)
resume el resultado y es el check requerido del ruleset de `main`, que se replica desde
`shoplab-helpdesk`. Toda la lógica vive en `scripts/*.sh` y las versiones de herramientas en un único
`versions.env`, de modo que `make check` ejecuta en local exactamente lo mismo que CI. Ver
[research.md](research.md) para las decisiones.

## Technical Context

**Language/Version**: GitHub Actions (YAML), Bash (Git Bash en Windows), `make`. Terraform >= 1.10 según LAB-98;
versiones exactas de todas las herramientas fijadas en `versions.env` (se resuelven en la tarea T001).

**Primary Dependencies**: `hashicorp/setup-terraform`, TFLint, kustomize, kubeconform (+ catálogo de esquemas de CRDs),
`yq`, Trivy (`aquasecurity/trivy-action`, ya usada en el repo de la app), `github/codeql-action/upload-sarif`,
`actions/github-script` (comentario del plan), imagen `floci/floci` con tag fijo (`2.3.0` según LAB-98).
Acciones fijadas por SHA o versión mayor como en `ci.yml` de `shoplab-helpdesk`.

**Storage**: N/A. El estado de Terraform del plan/humo vive en el S3 de un Floci efímero del runner.

**Testing**: el pipeline se prueba con PRs de "mutación" (una violación por regla) descritos en
[quickstart.md](quickstart.md); las políticas llevan casos de prueba propios (fixtures buenas/malas en
`scripts/tests/`), ejecutables con `make test-policies` y en CI.

**Target Platform**: runners `ubuntu-latest` de GitHub (Docker disponible); desarrollo local en Windows/macOS/Linux
con Docker y Git Bash.

**Project Type**: repositorio de infraestructura (CI/CD de IaC); sin servicios ni endpoints HTTP.

**Performance Goals**: controles estáticos < 10 min (SC-003); plan comentado < 10 min (SC-005); prueba de humo
< 20 min (SC-004). Cachés de plugins de Terraform y TFLint para acortar.

**Constraints**: sin credenciales ni cuenta reales de AWS (credenciales ficticias hacia Floci); permisos mínimos por job;
PR de forks sin escritura (sin comentario ni SARIF, solo resumen del job); sin `latest` ni rangos abiertos en nada
del pipeline; `ci-gate` siempre reporta estado.

**Scale/Scope**: un repositorio, un workflow principal, tres overlays, un entorno Terraform (`envs/local`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | N/A: no hay código de aplicación. |
| II. Arquitectura hexagonal | N/A: infraestructura. |
| III. Identificadores en inglés | Cumple: jobs, scripts, variables y targets en inglés; docs en español. |
| IV. Test-first con gate de cobertura | Cumple en espíritu: las políticas se escriben con fixtures buenas/malas antes de activarse. JaCoCo no aplica (sin Java). |
| V. RAG evaluado | N/A (F0). |
| VI. Observabilidad y manejo seguro | Cumple: credenciales ficticias, permisos mínimos por job, SARIF a Security, sin secretos en el repo. |
| VII. Simplicidad y decisiones documentadas | Cumple: scripts de shell + herramientas estándar, sin framework de políticas; la elección se justifica en R3. |
| Infraestructura: "Kubernetes llega en F7" | Misma desviación ya aceptada en LAB-98 (historia de F0, solo emulación local). |
| Flujo / commits | Cumple: rama `chore/LAB-99-…`; título de PR `chore(ci): [LAB-99] …`. |

Gate: **PASA**. Re-evaluado tras el diseño de Fase 1: sin cambios.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-99-infra-repo-pipeline/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── ci-workflow.md         # jobs, triggers, permisos, checks requeridos
│   ├── policy-rules.md        # reglas de manifiestos y formato de mensajes
│   └── make-check.md          # targets y paridad con CI
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks (no lo crea este comando)
```

### Source Code (repositorio `shoplab-infra`)

```text
shoplab-infra/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                 # changes, static, kustomize, trivy, plan, smoke, ci-gate
│   │   └── pr-title.yml           # igual que el repo de la app
│   ├── rulesets/
│   │   └── main-protection.json   # copia del ruleset de la app (checks: ci-gate, pr-title)
│   └── pull_request_template.md
├── versions.env                   # versiones de herramientas, única fuente para CI y make
├── .tflint.hcl                    # plugin AWS + reglas fijadas
├── .trivyignore                   # excepciones con motivo, responsable y fecha
├── Makefile                       # + check, fmt-check, validate, lint, tf-test, kustomize, policies, trivy, plan, smoke
├── scripts/
│   ├── check-tools.sh             # verifica versiones y dice qué falta
│   ├── kustomize-build.sh         # construye y valida (kubeconform) cada overlay
│   ├── policy-check.sh            # imágenes, repoURL de plantilla, targetRevision
│   ├── tf-plan-comment.sh         # arma el cuerpo del comentario (truncado)
│   ├── smoke.sh                   # apply, espera Argo CD, destroy (reusa scripts de LAB-98)
│   └── tests/
│       ├── policy-check.test.sh
│       └── fixtures/{good,bad-latest,bad-notag,bad-repourl,bad-revision}/
├── terraform/ …                   # LAB-98
└── kubernetes/overlays/{dev,staging,prod}/   # ruta a confirmar (ver Riesgos)
```

**Structure Decision**: la lógica de cada control vive en un script invocado igual por el workflow y por `make`
(paridad FR-019/SC-006); el workflow solo orquesta (checkout, instalar herramientas, llamar al script, subir
SARIF/comentario). Esta spec, plan y tareas permanecen en `shoplab-helpdesk/specs/` como en LAB-98; el código
se implementa en `shoplab-infra`.

## Riesgos y puntos abiertos

- **Overlays aún inexistentes**: LAB-99 depende de LAB-98, pero la creación de `kubernetes/overlays/*` no aparece
  en el plan de LAB-98. Como FR-008 exige que un overlay ausente falle, si no existen al implementar hay que
  coordinar con la historia del sprint que los crea (objetivo del Sprint 5) o crear overlays mínimos. La ruta se
  parametriza (`KUSTOMIZE_DIR` en `versions.env`).
- **Plan siempre "crear todo"**: el estado del plan vive en un Floci efímero, por lo que el comentario muestra el
  plan completo de creación, no un diff contra un entorno persistente. Es lo esperado para IaC local (R5).
- **Floci como service container**: necesita `docker.sock` (crea k3s). Validar en T001 que
  `services.<id>.volumes` funciona en el runner; plan B: levantar Floci con `docker compose` en un paso.
- **Duración de la prueba de humo**: LAB-98 mide < 15 min en 16 GB; el runner tiene 7 GB / 2 vcpu en repos
  privados (4 vcpu / 16 GB en públicos). Si no cabe, correrla solo en `main` y `workflow_dispatch` (R6).

## Complexity Tracking

| Violación | Por qué es necesaria | Alternativa más simple descartada porque |
|---|---|---|
| Scripts de shell con fixtures de prueba | Las políticas deben ser idénticas en CI y local y estar probadas | Lógica en línea en el YAML no se puede ejecutar en local ni probar |
| `versions.env` como fuente única | FR-020: mismas versiones en CI y `make check` | Versiones duplicadas en workflow y Makefile se desalinean |
