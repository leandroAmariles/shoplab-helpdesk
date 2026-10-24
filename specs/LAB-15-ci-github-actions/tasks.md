---

description: "Task list for LAB-15 - Pipeline de CI en GitHub Actions"
---

# Tasks: Pipeline de CI en GitHub Actions para Java, Angular y Python

**Input**: Design documents from `/specs/LAB-15-ci-github-actions/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ci-checks.md, quickstart.md

**Tests**: No se pidieron tests de código. Las tareas de validación ejecutan los escenarios de
[quickstart.md](quickstart.md) contra el propio pipeline (`actionlint`, build local y PRs de prueba).

**Organization**: Tareas agrupadas por historia de usuario. Casi todo el código vive en un único
archivo (`.github/workflows/ci.yml`), por eso las tareas que lo editan son secuenciales y no llevan
`[P]`; solo se marcan `[P]` las que tocan archivos distintos.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US6)

## Path Conventions

- Workflow: `.github/workflows/ci.yml`
- Backend: `backend/` (Maven, `backend/pom.xml`, `backend/mvnw`)
- Documentación del feature: `specs/LAB-15-ci-github-actions/`
- Estilo: YAML con 2 espacios, comentarios solo donde el motivo no sea obvio; versiones de acciones
  fijadas por tag mayor (`actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`,
  `actions/upload-artifact@v4`, `astral-sh/setup-uv@v6`, `dorny/paths-filter@v3`)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dejar el repositorio listo para que un runner Linux ejecute el wrapper de Maven y crear
el esqueleto del workflow.

- [X] T001 Marcar `backend/mvnw` como ejecutable en el índice de git con
  `git update-index --chmod=+x backend/mvnw` y comprobar con `git ls-files -s backend/mvnw` que el
  modo sea `100755` (hoy es `100644`; sin esto `./mvnw` falla con "Permission denied" en
  `ubuntu-latest`)
- [X] T002 [P] Añadir la línea `mvnw text eol=lf` a `.gitattributes` (conservar `* text=auto` y
  `*.sh text eol=lf`) para que un checkout con CRLF no rompa el shebang del wrapper
- [X] T003 [P] Crear `.github/workflows/ci.yml` con el esqueleto: `name: CI`; triggers `pull_request`
  y `push` con `branches: [main]` (sin `paths:` a nivel de trigger); `permissions: contents: read`;
  `concurrency` con `group: ci-${{ github.workflow }}-${{ github.ref }}` y
  `cancel-in-progress: ${{ github.event_name == 'pull_request' }}` (FR-001, FR-002, FR-014, FR-016)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Cálculo de qué componentes cambiaron; todos los jobs de componente dependen de él.

**CRITICAL**: Ninguna historia puede empezar hasta completar esta fase.

- [X] T004 Añadir a `.github/workflows/ci.yml` el job `changes` (`runs-on: ubuntu-latest`,
  `timeout-minutes: 5`, `actions/checkout@v4` y `dorny/paths-filter@v3`) que publique las salidas
  `backend` (`backend/**`), `frontend` (`frontend/**`), `ingest` (`ingest/**`), `eval` (`eval/**`) y
  `ci` (`.github/workflows/ci.yml`), tal como define la tabla de filtros de
  [data-model.md](data-model.md) (FR-003, FR-004)

**Checkpoint**: El workflow valida (`actionlint`) y `changes` expone las cinco salidas.

---

## Phase 3: User Story 1 - Validación automática del backend en cada cambio (Priority: P1) MVP

**Goal**: Cada PR y push a `main` que toque el backend compila y pasa estilo, análisis estático,
prueba de Modulith y pruebas de integración.

**Independent Test**: PR que modifica un archivo de `backend/` ejecuta el job `backend` en verde; una
violación de Checkstyle lo pone en rojo (quickstart escenarios 1 y 2).

### Implementation for User Story 1

- [X] T005 [US1] Añadir a `.github/workflows/ci.yml` el job `backend`: `needs: changes`,
  `if: needs.changes.outputs.backend == 'true' || needs.changes.outputs.ci == 'true'`,
  `timeout-minutes: 20`, `defaults.run.working-directory: backend`, pasos `actions/checkout@v4` y
  `actions/setup-java@v4` con `distribution: temurin`, `java-version: '21'`, `cache: maven` y
  `cache-dependency-path: backend/pom.xml` (FR-005)
- [X] T006 [US1] En el job `backend` de `.github/workflows/ci.yml`, añadir el paso
  `./mvnw -B -ntp clean verify` (ejecuta Checkstyle, PMD, `ModularityTests`, pruebas de integración
  y `jacoco:check`, todos enlazados a `verify` en `backend/pom.xml`) (FR-006, FR-007)
- [X] T007 [US1] Validar localmente: `actionlint .github/workflows/ci.yml` sin errores y
  `cd backend && ./mvnw -B -ntp clean verify` con `BUILD SUCCESS` (JDK 21 y Docker disponibles)
- [ ] T008 [US1] Validar en GitHub (quickstart escenarios 1, 2 y 9): PR de prueba con un cambio en
  `backend/` corre `backend` en verde; añadir una violación de Checkstyle en una rama descartable lo
  pone en rojo; tras fusionar, el push a `main` ejecuta el mismo job. Verificar en un segundo run que
  los pasos de Maven reportan *cache hit* (US1.4)

**Checkpoint**: US1 funcional y verificable por sí sola (MVP del pipeline).

---

## Phase 4: User Story 2 - Gate de cobertura del 80 % con reporte descargable (Priority: P1)

**Goal**: El job falla si la cobertura de líneas baja del 80 % y siempre deja el reporte.

**Independent Test**: Con cobertura >= 80 % el job pasa y hay artefacto; con < 80 % falla y el
artefacto igualmente existe (quickstart escenario 3).

### Implementation for User Story 2

- [X] T009 [US2] En el job `backend` de `.github/workflows/ci.yml`, añadir al final un paso
  `actions/upload-artifact@v4` con `if: always()`, `name: jacoco-report`,
  `path: backend/target/site/jacoco/`, `if-no-files-found: warn` y `retention-days: 14`
  (FR-008, FR-009). No añadir una invocación aparte de `jacoco:check`: ya corre dentro de `verify`
- [ ] T010 [US2] Validar el gate en una rama descartable (no se commitea): subir
  `jacoco.minimum.coverage` en `backend/pom.xml` por encima de la cobertura real y comprobar que
  `backend` falla por `jacoco:check` **y** que el artefacto `jacoco-report` está descargable;
  revertir el cambio
- [ ] T011 [US2] Validar el límite: con el umbral igual a la cobertura real (frontera del 80 %,
  US2.4) el job pasa, y con el valor `0.80` original del `pom.xml` el job vuelve a estar en verde

**Checkpoint**: US1 y US2 completas; el backend queda protegido por el gate de la constitución.

---

## Phase 5: User Story 3 - Ejecución solo de lo que cambió (Priority: P2)

**Goal**: Solo se construyen los componentes afectados y un check estable agrega el resultado.

**Independent Test**: PR solo de docs no construye componentes; PR que toca el workflow los construye
todos (quickstart escenarios 4 y 5).

### Implementation for User Story 3

- [X] T012 [US3] Añadir a `.github/workflows/ci.yml` el job `ci-gate` con `if: always()`,
  `needs: [changes, backend]` (se amplía en T016 y T019) y un paso que falle si
  `contains(needs.*.result, 'failure') || contains(needs.*.result, 'cancelled')` y pase si los
  resultados son `success` o `skipped` (FR-013, FR-017)
- [ ] T013 [US3] Validar en GitHub (quickstart escenario 4): PR que solo edita `README.md` o `docs/`
  deja `backend` omitido y `ci-gate` en verde, con cero builds de componentes (SC-003)
- [ ] T014 [US3] Validar en GitHub (quickstart escenario 5): PR que edita
  `.github/workflows/ci.yml` ejecuta todos los jobs de componente existentes (US3.5)
- [ ] T015 [US3] Validar la cancelación (quickstart escenario 8): empujar un segundo commit a un PR
  en curso y comprobar que la ejecución anterior se cancela y que en `main` no se cancelan runs

**Checkpoint**: Filtros por ruta y `ci-gate` operativos; el check requerido nunca queda pendiente.

---

## Phase 6: User Story 4 - Validación del frontend Angular (Priority: P2)

**Goal**: Lint, pruebas y build de Angular con caché de npm, sin falsos fallos mientras `frontend/`
esté vacío.

**Independent Test**: Con `frontend/` vacío el job termina en verde con aviso; con un proyecto Angular
corre lint, test y build en orden (quickstart escenario 6).

### Implementation for User Story 4

- [X] T016 [US4] Añadir a `.github/workflows/ci.yml` el job `frontend`: `needs: changes`,
  `if: needs.changes.outputs.frontend == 'true' || needs.changes.outputs.ci == 'true'`,
  `timeout-minutes: 15`, `defaults.run.working-directory: frontend` solo en los pasos que lo
  requieran, paso `detect` que publique `exists=true` si existe `frontend/package.json` (y emita un
  `::notice::` si no existe), y `actions/checkout@v4`. Incluir `frontend` en `needs` de `ci-gate`
  (FR-010, FR-012)
- [X] T017 [US4] En el job `frontend` de `.github/workflows/ci.yml`, añadir (todos con
  `if: steps.detect.outputs.exists == 'true'`) `actions/setup-node@v4` con `node-version: 22`,
  `cache: npm` y `cache-dependency-path: frontend/package-lock.json`, y luego `npm ci`,
  `npm run lint`, `npm test -- --watch=false --browsers=ChromeHeadless` y `npm run build`, en ese orden
  (FR-010)
- [ ] T018 [US4] Validar con `frontend/` vacío (estado actual): PR de prueba que toque `frontend/`
  deja `frontend` en verde con el aviso y `ci-gate` en verde. Anotar que la validación completa con
  proyecto Angular queda pendiente hasta la historia que cree `frontend/`

**Checkpoint**: Job Angular listo; se activa solo cuando exista `frontend/package.json`.

---

## Phase 7: User Story 5 - Validación de los servicios Python (Priority: P2)

**Goal**: ruff y pytest sobre `ingest/` y `eval/` con caché de uv y resultados independientes.

**Independent Test**: Con las carpetas vacías ambos jobs terminan en verde con aviso; con un proyecto
Python corren ruff y pytest (quickstart escenario 6, análogo para Python).

### Implementation for User Story 5

- [X] T019 [US5] Añadir a `.github/workflows/ci.yml` el job `python` con
  `strategy: { fail-fast: false, matrix: { component: [ingest, eval] } }`, `needs: changes`,
  `if: needs.changes.outputs[matrix.component] == 'true' || needs.changes.outputs.ci == 'true'`,
  `timeout-minutes: 15`, paso `detect` que publique `exists=true` si existe
  `${{ matrix.component }}/pyproject.toml` (con `::notice::` si no), y `actions/checkout@v4`. Incluir
  `python` en `needs` de `ci-gate` (FR-011, FR-012, FR-013)
- [X] T020 [US5] En el job `python` de `.github/workflows/ci.yml`, añadir (con
  `if: steps.detect.outputs.exists == 'true'`, `working-directory: ${{ matrix.component }}`)
  `astral-sh/setup-uv@v6` con `enable-cache: true` y
  `cache-dependency-glob: ${{ matrix.component }}/uv.lock`, y luego `uv sync --frozen`,
  `uv run ruff check .` y `uv run pytest`. Un `pytest` sin tests (código 5) debe fallar el job
  (FR-011)
- [ ] T021 [US5] Validar con las carpetas vacías (estado actual): PR de prueba que toque `ingest/` o
  `eval/` deja solo la entrada de matriz correspondiente en verde con aviso, y la otra omitida.
  Anotar que la validación completa con proyecto Python queda pendiente hasta la historia que cree
  esos servicios

**Checkpoint**: Job Python listo; se activa solo cuando exista `pyproject.toml`.

---

## Phase 8: User Story 6 - Estado del pipeline visible en el README (Priority: P3)

**Goal**: Badge del estado de `main` en el README.

**Independent Test**: El README en GitHub muestra el badge y enlaza a las ejecuciones.

### Implementation for User Story 6

- [X] T022 [US6] Añadir a `README.md`, justo debajo del título `# ShopLab Helpdesk`, el badge
  `[![CI](https://github.com/leandroAmariles/shoplab-helpdesk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/leandroAmariles/shoplab-helpdesk/actions/workflows/ci.yml)`
  (FR-015)
- [ ] T023 [US6] Validar tras fusionar a `main`: el badge se renderiza con el estado de la última
  ejecución en `main` y el clic abre `actions/workflows/ci.yml` (quickstart sección 3)

**Checkpoint**: Las seis historias completas.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T024 [P] Documentar en `README.md` (sección breve "CI") el comando local equivalente
  `cd backend && ./mvnw -B -ntp clean verify` y que en *Settings > Branches* debe exigirse
  únicamente el check `ci-gate` (contrato en [contracts/ci-checks.md](contracts/ci-checks.md))
- [ ] T025 Ejecutar `actionlint .github/workflows/ci.yml` final y recorrer la tabla completa de
  [quickstart.md](quickstart.md); marcar en este archivo las validaciones que quedaron diferidas
  (T018 y T021) y por qué
- [ ] T026 Revisión previa al commit según `CLAUDE.md` 8.4 y 8.5: `git status --short` sin archivos
  sueltos, `git diff --staged` con solo cambios intencionales (workflow, `.gitattributes`, modo de
  `mvnw`, README, specs)
- [ ] T027 Commits atómicos con el formato de la constitución `LAB-15 type(scope): description` (por
  ejemplo `LAB-15 chore(ci): add github actions pipeline`); abrir el PR con la plantilla de
  `.github/pull_request_template.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T002 y T003 en paralelo, T001 independiente.
- **Foundational (Phase 2)**: depende de T003; bloquea todas las historias.
- **US1 (P1)** -> **US2 (P1)**: US2 edita el mismo job `backend` que crea US1.
- **US3 (P2)**: depende de US1 (necesita el job `backend`); T016 y T019 añaden sus jobs a `needs`
  de `ci-gate`.
- **US4 y US5 (P2)**: dependen de Foundational y de que exista `ci-gate` (T012) para registrarse;
  son independientes entre sí en lógica, pero editan el mismo archivo, así que se aplican en serie.
- **US6 (P3)**: independiente del resto (solo `README.md`); su validación requiere workflow en `main`.
- **Polish**: depende de las historias que se vayan a entregar.

### Parallel Opportunities

- T002 y T003 (archivos distintos).
- T022 (README) puede hacerse en paralelo con cualquier tarea de `ci.yml`; T024 también.
- Validaciones de GitHub (T013, T014, T015, T018, T021) pueden compartir PRs de prueba.

### Parallel Example

```text
T002 .gitattributes (mvnw eol=lf)   |   T003 esqueleto de ci.yml   |   T022 badge en README.md
```

## Implementation Strategy

### MVP First (US1 + US2)

1. Setup y Foundational (T001-T004).
2. US1 (T005-T008): el backend ya queda validado en CI.
3. US2 (T009-T011): gate de cobertura bloqueante con reporte.
4. **Detenerse y validar**: el pipeline cumple las exigencias de la constitución para el único
   componente con código hoy.

### Incremental Delivery

1. MVP -> US3 (`ci-gate` y filtros, necesario antes de exigir checks en la rama).
2. US4 y US5 (jobs listos pero latentes hasta que existan los proyectos).
3. US6 (badge) y Polish.

## Notes

- No hay endpoints HTTP en esta historia: no corresponde generar `requests.http`.
- Las tareas de validación en GitHub requieren push de ramas de prueba; no ejecutarlas sin acordar
  con la persona dueña del repositorio, ya que consumen minutos de CI y publican ramas.
- Total: 27 tareas.
