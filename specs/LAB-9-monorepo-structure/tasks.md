---

description: "Task list for LAB-9 Monorepo con la estructura acordada"
---

# Tasks: Monorepo con la estructura acordada

**Input**: Design documents from `/specs/LAB-9-monorepo-structure/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (no `contracts/`)

**Tests**: No se piden tests automatizados; la verificación se hace con los comandos de
[quickstart.md](quickstart.md).

**Organization**: Tareas agrupadas por historia de usuario. Commits: `LAB-9 type(scope): description`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: Historia a la que pertenece (US1, US2, US3)

## Phase 1: Setup (línea base)

**Purpose**: Confirmar el estado heredado del commit `LAB-1` antes de editar.

- [X] T001 Confirmar la rama con `git branch --show-current` (debe ser `chore/LAB-9-monorepo-structure`) y listar `git ls-files` para registrar qué existe ya (carpetas con `.gitkeep`, `README.md`, `.editorconfig`)

> No hay fase Foundational: no existen prerrequisitos bloqueantes para las tres historias.

---

## Phase 2: User Story 1 - Estructura por tecnología (Priority: P1) MVP

**Goal**: Las seis carpetas acordadas existen y están versionadas en un clon limpio (FR-001, FR-002).

**Independent Test**: Quickstart paso 1 imprime `OK` para `frontend`, `backend`, `ingest`, `eval`, `infra`, `docs`.

- [X] T002 [US1] Ejecutar el paso 1 de `specs/LAB-9-monorepo-structure/quickstart.md` y confirmar seis líneas `OK`
- [X] T003 [P] [US1] Crear `.gitkeep` en cualquiera de `frontend/`, `backend/`, `ingest/`, `eval/`, `infra/` que no tenga archivos versionados; `docs/` ya queda cubierta por `docs/adr/` y `docs/fases/.gitkeep`
- [X] T004 [P] [US1] Confirmar que `data/kb/.gitkeep` y `notebooks/.gitkeep` existen, ya que se conservan como carpetas auxiliares

**Checkpoint**: Estructura verificada.

---

## Phase 3: User Story 2 - README con cómo arrancar (Priority: P1)

**Goal**: README completo con prerrequisitos, arranque, convenciones y licencia (FR-003, FR-004, FR-005, FR-008).

**Independent Test**: Quickstart pasos 2 y 3 sin diferencias y con coincidencias en las secciones esperadas.

- [X] T005 [US2] En `README.md`, ampliar la tabla de estructura: añadir `notebooks/` y `data/` si faltan, y verificar que cada fila corresponde a una carpeta real (quickstart paso 2)
- [X] T006 [US2] En `README.md`, reemplazar la sección `## Arrancar` por: lista de prerrequisitos (git, Docker, JDK 21, Node, Python), el comando `docker compose -f infra/docker-compose.yml up` marcado explícitamente como "disponible desde F0-02 (`LAB`)", sin comandos por componente
- [X] T007 [US2] En `README.md`, añadir sección `## Convenciones` con ramas `<type>/LAB-<n>-<slug>`, commits `LAB-<n> type(scope): description` y ubicación del backlog (Jira `LAB`) y de los ADR (`docs/adr/`)
- [X] T008 [US2] En `README.md`, añadir sección `## Licencia` al final: "MIT, ver [LICENSE](LICENSE)"
- [X] T009 [US2] Ejecutar los pasos 2 y 3 de `specs/LAB-9-monorepo-structure/quickstart.md`; corregir `README.md` hasta que no haya diferencias

**Checkpoint**: README coincide con la estructura real.

---

## Phase 4: User Story 3 - Estilo de edición y licencia (Priority: P2)

**Goal**: `.editorconfig` y `LICENSE` presentes y reconocibles (FR-006, FR-007, FR-009).

**Independent Test**: Quickstart pasos 4 y 5.

- [X] T010 [P] [US3] Crear `LICENSE` en la raíz con el texto estándar de la licencia MIT y la línea `Copyright (c) 2026 Leandro Amariles`
- [X] T011 [P] [US3] Verificar `.editorconfig` raíz: `root = true`, `charset = utf-8`, `end_of_line = lf`, `indent_style = space`, `insert_final_newline = true`; corregir solo si falta algo
- [X] T012 [P] [US3] Verificar que `.gitignore` excluye `data/raw/`, `.claude/settings.local.json` y `specs/*/.token-usage.json`; añadir solo lo que falte
- [X] T013 [US3] Ejecutar los pasos 4 y 5 de `specs/LAB-9-monorepo-structure/quickstart.md` y confirmar `editorconfig OK`, cabecera de `LICENSE` correcta y `git status --short` sin archivos locales o generados

**Checkpoint**: Licencia y estilo verificados.

---

## Phase 5: Polish & cierre

- [X] T014 Revisar `git status --short` y `git diff` para confirmar que solo cambiaron `LICENSE`, `README.md` y, si fue necesario, `.gitignore` o `.gitkeep`; eliminar archivos temporales (`/tmp/real.txt`, `/tmp/readme.txt`)
- [ ] T015 Commit atómico por cambio lógico con formato `LAB-9 docs(readme): ...` y `LAB-9 chore(license): add MIT license`; abrir el PR con `.github/pull_request_template.md` (historia `LAB-9`)

---

## Dependencies & Execution Order

- Phase 1 (T001) -> Phases 2-4 -> Phase 5.
- US1, US2 y US3 son independientes entre sí; US2 T008 referencia `LICENSE`, por lo que T010 debe existir antes de T009/T013.
- Dentro de cada historia, las tareas [P] tocan archivos distintos.

### Parallel Opportunities

- T003 y T004 en paralelo.
- T010, T011 y T012 en paralelo.
- US1 y US3 pueden avanzar a la vez; US2 edita solo `README.md` (secuencial T005 -> T009).

## Implementation Strategy

- **MVP**: Phase 1 + US1 (estructura verificada).
- **Incremento**: US2 (README) y luego US3 (licencia y estilo); validar con el quickstart tras cada una.
- Esta historia no genera endpoints, por lo que no se crea `requests.http`.
