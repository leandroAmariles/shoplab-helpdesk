---

description: "Task list for LAB-75 - Análisis estático con Qodana en el backend"
---

# Tasks: Análisis estático con Qodana en el backend

**Input**: Design documents from `/specs/LAB-75-qodana-backend-analysis/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/qodana-check.md, quickstart.md

**Tests**: No se pidieron tests de código. Las tareas de validación ejecutan los escenarios de
[quickstart.md](quickstart.md) contra el propio pipeline (`actionlint` y PRs de prueba).

**Organization**: Tareas agrupadas por historia de usuario. Casi todo el cambio vive en dos archivos
(`.github/workflows/ci.yml` y `backend/qodana.yaml`), por eso las tareas que editan el mismo archivo
son secuenciales y no llevan `[P]`; solo se marcan `[P]` las que tocan archivos distintos.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US4)

## Path Conventions

- Workflow: `.github/workflows/ci.yml` (job nuevo `qodana`, existente `ci-gate`)
- Configuración de Qodana: `backend/qodana.yaml`; baseline: `backend/qodana.sarif.json`
- Documentación del feature: `specs/LAB-75-qodana-backend-analysis/`
- Estilo: YAML con 2 espacios, comentarios solo donde el motivo no sea obvio; versiones de acciones
  fijadas por tag mayor como en LAB-15 (`actions/checkout@v4`, `JetBrains/qodana-action@v2026.2.2`,
  `github/codeql-action/upload-sarif@v4`)
- Commits: `LAB-75 type(scope): description in english`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirmar los supuestos externos antes de escribir configuración que dependa de ellos.

- [X] T001 Comprobar que existe el tag móvil `v2026.2` de la acción con
  `gh api repos/JetBrains/qodana-action/git/ref/tags/v2026.2`; si no existe, usar `v2026.2.2` en
  todas las tareas siguientes y anotarlo en `specs/LAB-75-qodana-backend-analysis/research.md`
  (decisión 9)
- [X] T002 [P] Comprobar si Code Scanning está habilitado en el repositorio con
  `gh api repos/leandroAmariles/shoplab-helpdesk/code-scanning/alerts` (un 404/403 indica que no);
  anotar el resultado en `specs/LAB-75-qodana-backend-analysis/research.md` (decisión 5) para decidir
  si el paso `upload-sarif` (T016) lleva `continue-on-error: true`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuración base de Qodana y esqueleto del job, integrado en el check agregador.

**CRITICAL**: Ninguna historia puede empezar hasta completar esta fase.

- [X] T003 [P] Crear `backend/qodana.yaml` con `version: "1.0"`, `linter: qodana-jvm-community`,
  `profile.name: qodana.recommended` y exclusión del análisis de `target/` y del código generado
  mediante `inspections` con `group: ALL` e `ignore` (FR-003, FR-004, FR-015); sin umbrales todavía
- [X] T004 Añadir a `.github/workflows/ci.yml` el job `qodana` con `needs: changes`,
  `if: needs.changes.outputs.backend == 'true' || needs.changes.outputs.ci == 'true'`,
  `runs-on: ubuntu-latest`, `timeout-minutes: 20`, permisos de job `contents: read`,
  `security-events: write`, `pull-requests: write`, `checks: write` (FR-012) y los pasos
  `actions/checkout@v4` y `JetBrains/qodana-action@v2026.2.2` con `args: --project-dir,backend`,
  `pr-mode: false`, `use-caches: true`, `upload-result: true` y `artifact-name: qodana-report`
  (FR-001, FR-002, FR-011); el job hereda la concurrencia del workflow (FR-014)
- [X] T005 Añadir `qodana` a la lista `needs` del job `ci-gate` en `.github/workflows/ci.yml`, de modo
  que `ci-gate` siga siendo el único check requerido y trate `qodana` omitido como válido (FR-013)
- [ ] T006 Validar sintaxis con `actionlint` sobre `.github/workflows/ci.yml` (o inspección manual si
  no está instalado) y corregir errores

**Checkpoint**: El job existe y se ejecuta en PRs de backend; todavía sin gate ni baseline.

---

## Phase 3: User Story 1 - Bloqueo de problemas nuevos de severidad alta (Priority: P1) 🎯 MVP

**Goal**: El PR falla si introduce problemas nuevos de severidad alta o crítica; severidades
inferiores quedan informativas; PRs sin backend omiten el análisis.

**Independent Test**: Quickstart escenarios 2, 3 y 4 (problema alto = rojo; problema bajo = verde;
PR de docs = omitido).

### Implementation for User Story 1

- [X] T007 [US1] Añadir a `backend/qodana.yaml` el bloque
  `failureConditions.severityThresholds` con `critical: 0` y `high: 0` y sin umbral para `moderate`,
  `low` e `info` (FR-006)
- [ ] T008 [US1] Ejecutar escenario 4 del quickstart: PR de prueba que solo toque `README.md`;
  comprobar que `qodana` queda `skipped` y `ci-gate` verde (FR-001, SC-006)
- [ ] T009 [US1] Ejecutar escenario 2 del quickstart: PR de prueba con un problema de severidad alta
  (p. ej. desreferencia nula evidente) en una clase de `backend/src/main/java/`; comprobar que
  `qodana` y `ci-gate` fallan indicando el problema; descartar la rama de prueba sin fusionar
  (SC-002)
- [ ] T010 [US1] Ejecutar escenario 3 del quickstart: PR de prueba con una advertencia de baja
  severidad; comprobar que `qodana` pasa y el problema solo se reporta como informativo (FR-006)

**Checkpoint**: El gate de severidad bloquea y distingue severidades (con la deuda heredada aún sin
baseline; ver US2).

---

## Phase 4: User Story 2 - Baseline inicial (Priority: P1)

**Goal**: La deuda heredada no bloquea; solo cuentan problemas nuevos.

**Independent Test**: Quickstart escenarios 0, 1 y 8.

### Implementation for User Story 2

- [X] T011 [US2] Generar el baseline inicial (quickstart escenario 0): ejecutar el job `qodana` sin
  baseline en la rama (o `qodana scan --project-dir backend` en local), descargar `qodana.sarif.json`
  del artefacto `qodana-report` y guardarlo como `backend/qodana.sarif.json`
- [X] T012 [US2] Añadir `--baseline,qodana.sarif.json` a `args` del paso Qodana en
  `.github/workflows/ci.yml` (`--project-dir,backend,--baseline,qodana.sarif.json`); si la ruta no se
  resuelve respecto a `backend/`, usar `--baseline,backend/qodana.sarif.json` y documentarlo en
  `specs/LAB-75-qodana-backend-analysis/research.md` (decisión 3) (FR-005)
- [ ] T013 [US2] Ejecutar escenario 1 del quickstart: PR limpio (cambio de un comentario en
  `backend/src/`); comprobar que `qodana` pasa aunque el baseline contenga problemas altos. Si falla
  por lo heredado, aplicar el plan B de `research.md` decisión 4 y registrar el resultado (SC-003)
- [ ] T014 [US2] Ejecutar escenario 8 del quickstart: corregir un problema heredado y comprobar que
  desaparece sin marcarse como regresión; comprobar que un problema equivalente en código nuevo sí
  cuenta contra el gate

**Checkpoint**: Gate operativo y adoptable (US1 + US2): MVP completo.

---

## Phase 5: User Story 3 - Resultados visibles en GitHub (Priority: P2)

**Goal**: Hallazgos en la pestaña Security y comentario resumen del PR, también cuando el gate falla.

**Independent Test**: Quickstart escenario 6.

### Implementation for User Story 3

- [X] T015 [US3] Añadir al paso Qodana de `.github/workflows/ci.yml` los inputs
  `use-annotations: true`, `post-pr-comment: true` y `github-token: ${{ secrets.GITHUB_TOKEN }}`
  (FR-008)
- [X] T016 [US3] Añadir en `.github/workflows/ci.yml`, después del paso Qodana, el paso
  `github/codeql-action/upload-sarif@v4` con
  `sarif_file: ${{ runner.temp }}/qodana/results/qodana.sarif.json` y `if: always()`, de modo que se
  publique también cuando el gate falle; añadir `continue-on-error: true` si T002 mostró que Code
  Scanning no está disponible (FR-007)
- [ ] T017 [US3] Ejecutar escenario 6 del quickstart: sobre el PR de problema alto, comprobar los
  hallazgos en Security > Code scanning, el comentario y las anotaciones; empujar un segundo commit y
  comprobar que el comentario se actualiza y no se duplica (SC-004)

**Checkpoint**: Resultados visibles en GitHub en el 100 % de las ejecuciones completadas.

---

## Phase 6: User Story 4 - Configuración versionada y secretos fuera del código (Priority: P2)

**Goal**: Configuración reproducible en el repo; token solo como secreto; forks sin fallos ni
exposición.

**Independent Test**: Quickstart escenarios 5 y 7 y revisión de secretos.

### Implementation for User Story 4

- [X] T018 [US4] En `.github/workflows/ci.yml`, condicionar `post-pr-comment` y `use-annotations`
  del paso Qodana y el paso `upload-sarif` a
  `github.event.pull_request.head.repo.fork != true` (p. ej. con una expresión en los inputs y en el
  `if` del paso), de modo que en forks el análisis y el gate corran sin intentar escrituras (FR-010,
  FR-012)
- [X] T019 [US4] Si T011 o T013 mostraron que Community exige token, pasar `QODANA_TOKEN` solo vía
  `env: QODANA_TOKEN: ${{ secrets.QODANA_TOKEN }}` del paso Qodana, documentar en
  `specs/LAB-75-qodana-backend-analysis/research.md` (decisión 2) que el secreto debe crearse en
  Settings > Secrets, y verificar que el job no falla en forks (FR-009, FR-010); si no lo exige,
  anotar que no es necesario
- [ ] T020 [US4] Ejecutar escenario 5 del quickstart: PR que solo modifique `backend/qodana.yaml`;
  comprobar que `qodana` se ejecuta
- [ ] T021 [US4] Ejecutar escenario 7 del quickstart: PR desde un fork (o simulación equivalente);
  comprobar que no hay comentario, anotaciones ni SARIF, que no hay fallos por permisos y que
  ningún log contiene un token (SC-005)
- [X] T022 [US4] Revisar que ningún archivo versionado contiene el valor de un token:
  `git grep -nI -i "qodana_token"` debe mostrar solo la referencia `secrets.QODANA_TOKEN` (si se
  añadió) y la documentación

**Checkpoint**: Todas las historias funcionales de forma independiente.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentación, verificación final y registro de resultados.

- [X] T023 [P] Añadir a `README.md` una nota breve sobre el análisis Qodana: qué bloquea, dónde ver
  los resultados y cómo regenerar el baseline (ejecución sobre `main` + commit de
  `backend/qodana.sarif.json` en un PR dedicado) y cómo suprimir un falso positivo con `ignore` en
  `backend/qodana.yaml` (edge cases de la spec)
- [ ] T024 Confirmar que el job `backend` de LAB-15 (Checkstyle, PMD, Modulith, JaCoCo) sigue verde e
  independiente: un fallo de `qodana` no impide que `backend` se ejecute (FR-011) y no se modificó
  código Java (cobertura intacta)
- [ ] T025 Medir el tiempo de un análisis típico de PR con caché y contrastarlo con el objetivo de
  menos de 15 min (SC-007); si lo supera, evaluar `pr-mode: true` con `fetch-depth: 0` (research
  decisión 8)
- [ ] T026 Registrar en `specs/LAB-75-qodana-backend-analysis/research.md` el resultado de cada punto
  "Por validar" (token, umbrales con baseline, ruta del baseline, tag de la acción, Code Scanning) y
  cualquier ajuste al plan, como se hizo en LAB-15
- [ ] T027 Ejecutar la guía completa de `specs/LAB-75-qodana-backend-analysis/quickstart.md` y marcar
  el criterio de salida

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 y T002 pueden ir en paralelo
- **Foundational (Phase 2)**: depende de T001 (versión de la acción); T003 puede ir en paralelo con
  T004; T005 y T006 después de T004
- **US1 (Phase 3)**: depende de Foundational; T007 antes de T009 y T010
- **US2 (Phase 4)**: depende de Foundational; T011 depende de un job funcional (T004) y puede
  ejecutarse antes de T007; T012 depende de T011
- **US3 (Phase 5)**: depende de Foundational; T016 después de T015 (mismo archivo)
- **US4 (Phase 6)**: T018 depende de T015 y T016 (modifica los mismos pasos); T019 depende de T011/T013
- **Polish (Phase 7)**: depende de las historias deseadas

### User Story Dependencies

- **US1 (P1)** y **US2 (P1)**: independientes en implementación, pero el gate solo es adoptable con
  ambas; las validaciones T009/T010 se hacen mejor con el baseline de T011/T012 ya aplicado
- **US3 (P2)**: independiente; sus validaciones usan el PR de T009
- **US4 (P2)**: edita pasos creados en US3, por lo que se ejecuta después

### Within Each Phase

- Las tareas que editan `.github/workflows/ci.yml` son secuenciales
- La configuración (`backend/qodana.yaml`) puede avanzar en paralelo con el workflow salvo T007 → T009

### Parallel Opportunities

- T001 y T002 (consultas independientes)
- T003 (`backend/qodana.yaml`) y T004 (`ci.yml`)
- T023 (`README.md`) en paralelo con T024-T026

---

## Parallel Example: Foundational

```bash
Task: "Crear backend/qodana.yaml con linter, perfil y exclusiones"      # T003
Task: "Añadir el job qodana a .github/workflows/ci.yml"                  # T004
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Phase 1 y Phase 2: confirmar versiones, crear `qodana.yaml`, job y `ci-gate`
2. Phase 3 (US1): gate por severidad
3. Phase 4 (US2): baseline para que el gate sea adoptable
4. **STOP y VALIDAR**: escenarios 0-4 y 8 del quickstart
5. Abrir el PR de la rama

### Incremental Delivery

1. MVP (US1 + US2) → PR con gate y baseline
2. US3 → resultados en Security y PR
3. US4 → forks y secretos; Polish

---

## Notes

- [P] tasks = archivos distintos, sin dependencias
- [Story] mapea la tarea con la historia de la spec para trazabilidad
- Las ramas de prueba de T008-T010, T014, T017, T021 se descartan sin fusionar
- Commits atómicos: `LAB-75 chore(ci): ...`, `LAB-75 docs(spec): ...`
