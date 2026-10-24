# Implementation Plan: Escaneo de vulnerabilidades y secretos con Trivy

**Branch**: `chore/LAB-76-trivy-security-scan` | **Date**: 2026-10-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-76-trivy-security-scan/spec.md`

## Summary

Se añade un job `trivy` al workflow existente `.github/workflows/ci.yml` (LAB-15), sumado a las
`needs` de `ci-gate` para que el único check exigido en la protección de rama siga siendo `ci-gate`.
A diferencia de `backend`/`qodana`, el job **no** depende del filtro por ruta: corre en todo pull
request y en cada push a `main`, porque vulnerabilidades y secretos pueden aparecer en cualquier
archivo. Ejecuta `aquasecurity/trivy-action` en modo `fs` sobre la raíz con los escáneres `vuln`,
`secret` y `misconfig`, severidad `HIGH,CRITICAL` e `ignore-unfixed`. Usa dos pasadas sobre la misma
base de datos en caché: una que genera el SARIF (sin fallar) y lo sube a la pestaña Security, y otra
en formato tabla con `exit-code: 1` que actúa de gate y deja en el log paquete, versión afectada y
versión corregida. Se versionan `.trivyignore` (excepciones comentadas) y `.github/dependabot.yml`
(Maven, npm, pip, GitHub Actions, Docker). Decisiones en [research.md](research.md).

## Technical Context

**Language/Version**: YAML de GitHub Actions + YAML de Dependabot + archivo de texto `.trivyignore`.
No hay código de aplicación nuevo.

**Primary Dependencies**: `aquasecurity/trivy-action` (fijada por SHA de commit, ver research.md),
`github/codeql-action/upload-sarif@v4` y `actions/checkout@v4` (ya usadas en LAB-15/LAB-75).

**Storage**: N/A. La base de datos de vulnerabilidades de Trivy se cachea con la caché de la acción.

**Testing**: Validación del propio pipeline con PRs de prueba (ver [quickstart.md](quickstart.md)):
PR limpio (verde), PR con dependencia vulnerable con corrección (rojo), PR con secreto de prueba
(rojo), excepción en `.trivyignore` (verde). `actionlint` local opcional y ejecución local de
`trivy fs` para ensayar antes de abrir el PR. JaCoCo no se ve afectado.

**Target Platform**: Runner `ubuntu-latest` hospedado por GitHub.

**Project Type**: Infraestructura de CI de un monorepo; extiende el pipeline de LAB-15.

**Performance Goals**: Escaneo de un PR típico en < 10 min (SC-007).

**Constraints**: Sin secretos requeridos; permisos mínimos por job; PRs desde forks y de Dependabot
(token de solo lectura) no suben SARIF pero siguen aplicando el gate; el valor de un secreto nunca se
imprime; escaneo de imagen excluido (LAB-78).

**Scale/Scope**: 1 job nuevo en el workflow, 1 cambio en `ci-gate`, 2 archivos nuevos en la raíz
(`.trivyignore`, `.github/dependabot.yml`) y una nota breve en README.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / regla | Estado | Nota |
|---|---|---|
| I. Monolito modular primero | N/A | No toca módulos ni sus límites. |
| II. Hexagonal / dominio puro | N/A | Sin código de aplicación. |
| III. Identificadores en inglés | Pass | Job y archivos en inglés; documentación en español. |
| IV. Test-first con gate 80 % | Pass | No altera JaCoCo ni los tests; el escaneo es adicional. |
| V. RAG evaluado por PR (desde F2) | N/A | Fuera de alcance. |
| VI. Observabilidad y secretos | Pass | Refuerza la regla de no versionar secretos; sin secretos propios; permisos mínimos; los valores detectados no se imprimen. |
| VII. Simplicidad y ADR | Pass | Un job en el workflow existente, sin workflow nuevo ni configuración extra de Trivy. Sin decisión arquitectónica que amerite ADR. |
| Style gates (PMD/Checkstyle) | Pass | Sin código Java modificado. |
| Branch/commit naming | Pass | `chore/LAB-76-trivy-security-scan`; directorio igual al último segmento; commits `LAB-76 type(scope): ...`. |

**Re-check post-diseño**: Pass. Sin violaciones; Complexity Tracking vacío.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-76-trivy-security-scan/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── trivy-check.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no creado aquí)
```

### Source Code (repository root)

```text
.github/
├── dependabot.yml           # nuevo: maven, npm, pip, github-actions, docker (+ docker-compose)
└── workflows/
    └── ci.yml               # + job `trivy`; `ci-gate` lo incluye en `needs`
.trivyignore                 # nuevo: excepciones comentadas (inicialmente solo encabezado)
README.md                    # nota breve: escaneo Trivy y cómo añadir excepciones
```

**Structure Decision**: Job dentro de `ci.yml`, no un workflow aparte, para heredar la
concurrencia y el check agregador `ci-gate` de LAB-15 (research.md, decisión 1). `.trivyignore` y
`dependabot.yml` van en la raíz/`.github/` porque el escaneo cubre todo el repo y GitHub exige esa
ubicación para Dependabot.

## Complexity Tracking

Sin violaciones de la constitución que justificar.
