---

description: "Task list for LAB-77 - Protección de main con checks requeridos"
---

# Tasks: Protección de main con checks requeridos

**Input**: Design documents from `/specs/LAB-77-main-branch-protection/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/main-ruleset.md,
contracts/pr-title-check.md, quickstart.md

**Tests**: No se pidieron tests de código. La validación ejecuta los escenarios de
[quickstart.md](quickstart.md) (`actionlint`, validación del JSON, PRs de prueba y comandos `gh api`).

**Organization**: Tareas agrupadas por historia de usuario. Las historias 1, 2 y 4 construyen el mismo
archivo (`.github/rulesets/main-protection.json`) por capas, así que sus tareas son secuenciales y no
llevan `[P]`; solo se marcan `[P]` las que tocan archivos distintos. La activación en GitHub solo es
posible **después de fusionar** esta rama (research.md, decisión 7), por eso vive en la fase final.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US5)

## Path Conventions

- Workflow nuevo: `.github/workflows/pr-title.yml` (job `pr-title`); `ci.yml` no se modifica
- Ruleset versionado: `.github/rulesets/main-protection.json`
- Plantilla de PR: `.github/pull_request_template.md`; documentación: `README.md`
- Documentación del feature: `specs/LAB-77-main-branch-protection/`
- Estilo: YAML con 2 espacios, comentarios solo donde el motivo no sea obvio; acciones propias de
  GitHub por tag mayor como en LAB-15 (`actions/checkout@v4`); acciones de terceros fijadas por SHA
  completo con el tag en un comentario; JSON con 2 espacios
- Commits: `LAB-77 type(scope): description in english`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirmar los supuestos externos antes de escribir configuración que dependa de ellos.

- [X] T001 Obtener el último release publicado de `amannn/action-semantic-pull-request` y su SHA de
  commit completo con `gh api repos/amannn/action-semantic-pull-request/releases/latest --jq .tag_name`
  y `gh api repos/amannn/action-semantic-pull-request/git/ref/tags/<tag> --jq .object.sha` (si el
  objeto es un tag anotado, resolver al commit con `git/tags/<sha>`); anotar tag y SHA para T008.
- [X] T002 Confirmar los prerrequisitos de plataforma y anotar el resultado en
  `specs/LAB-77-main-branch-protection/research.md` (sección 9, "Plan del repositorio"):
  `gh auth status` con permisos de administrador sobre `leandroAmariles/shoplab-helpdesk`;
  `gh api repos/leandroAmariles/shoplab-helpdesk --jq .private` (público o plan con rulesets);
  que el job de `.github/workflows/ci.yml` se llama exactamente `ci-gate`; y el id de la app GitHub
  Actions para fijar los contextos (`15368`, verificable con
  `gh api repos/leandroAmariles/shoplab-helpdesk/rulesets` en un ruleset existente o en la
  documentación de la API).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Esqueleto del ruleset sobre el que las historias 1, 2, 3 y 4 añaden reglas.

**⚠️ CRITICAL**: Ninguna historia puede añadir reglas hasta que exista el archivo base.

- [X] T003 Crear `.github/rulesets/main-protection.json` con la base del ruleset: `name`
  `main-protection`, `target` `branch`, `enforcement` `active`, `bypass_actors` `[]` (sin bypass,
  FR-003), `conditions.ref_name.include` `["~DEFAULT_BRANCH"]` con `exclude` `[]` y `rules` `[]`.
  Verificar con `python -c "import json; json.load(open('.github/rulesets/main-protection.json'))"`.

**Checkpoint**: Archivo base válido - las historias pueden añadir reglas.

---

## Phase 3: User Story 1 - `main` solo recibe cambios vía pull request (Priority: P1) 🎯 MVP

**Goal**: Rechazar push directo, force push y borrado de `main`; exigir PR para todos, sin bypass.

**Independent Test**: Con el ruleset aplicado, `git push origin main` y `git push --force origin main`
son rechazados incluso para el propietario (quickstart paso 2).

### Implementation for User Story 1

- [X] T004 [US1] En `.github/rulesets/main-protection.json` añadir las reglas `deletion` y
  `non_fast_forward` (FR-002) y la regla `pull_request` con `required_approving_review_count` `0`,
  `dismiss_stale_reviews_on_push` `false`, `require_code_owner_review` `false`,
  `require_last_push_approval` `false` y `required_review_thread_resolution` `false` (FR-001; sin
  revisores por ser un repositorio de un solo desarrollador, ver Assumptions de la spec).

**Checkpoint**: El ruleset impide push directo, force push y borrado; el PR es la única vía.

---

## Phase 4: User Story 2 - Checks de calidad y seguridad requeridos antes de fusionar (Priority: P1)

**Goal**: Que build/tests con cobertura, Qodana y Trivy sean obligatorios para fusionar, sin bloquear
PRs que omiten componentes.

**Independent Test**: PR con un check fallido queda bloqueado; PR solo de docs queda fusionable
(quickstart paso 3).

### Implementation for User Story 2

- [X] T005 [US2] En `.github/rulesets/main-protection.json` añadir la regla `required_status_checks`
  con `required_status_checks` `[{"context": "ci-gate", "integration_id": 15368}]` (FR-004/005/006;
  `ci-gate` agrega `backend`, `qodana`, `trivy`, `frontend` y `python`, research.md decisión 1) y
  `strict_required_status_checks_policy` `false` por ahora (se activa en T011).
- [X] T006 [US2] Verificar en `.github/workflows/ci.yml` que `ci-gate` tiene `if: always()` y que su
  `needs` incluye `changes`, `backend`, `qodana`, `trivy`, `frontend` y `python`, y que el nombre del
  job no cambió; no modificar el archivo (FR-018). Si falta algún job, detener y reportar.

**Checkpoint**: Los gates de LAB-15, LAB-75 y LAB-76 son obligatorios mediante un único contexto.

---

## Phase 5: User Story 3 - Título del PR con Conventional Commits y clave LAB (Priority: P1)

**Goal**: Validar el título del PR (`type(scope): [LAB-<n>] descripción`) y exigir ese check.

**Independent Test**: Título `arreglos varios` → rojo; corregirlo a
`feat(chat): [LAB-12] respuesta en streaming` → verde sin nuevo commit (quickstart paso 4).

### Implementation for User Story 3

- [X] T007 [P] [US3] Actualizar `.github/pull_request_template.md`: bajo `## Historia` indicar que
  el título del PR debe seguir `type(scope): [LAB-<n>] descripción` (ejemplo
  `feat(chat): [LAB-12] respuesta en streaming`) porque es el mensaje del commit de squash, y añadir
  el ítem `- [ ] Título del PR en formato Conventional Commits con la clave [LAB-<n>]` al checklist.
- [X] T008 [US3] Crear `.github/workflows/pr-title.yml` con `name: PR title`, disparador
  `pull_request` con `types: [opened, edited, synchronize, reopened]` (FR-009), `permissions:
  pull-requests: read` (FR-019), `concurrency` por `github.ref` con `cancel-in-progress: true`, y un
  único job `pr-title` (nombre estable, `runs-on: ubuntu-latest`, `timeout-minutes: 5`, sin checkout).
  El job tiene dos pasos con `amannn/action-semantic-pull-request` fijada al SHA de T001 (tag en
  comentario) y `env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}`:
  1. `if: github.actor != 'dependabot[bot]'`: `types` con exactamente `feat`, `fix`, `perf`,
     `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert`; `requireScope: false`;
     `subjectPattern: ^\[LAB-\d+\] .+$` y `subjectPatternError` que muestre el formato esperado
     `type(scope): [LAB-<n>] descripción` y cuál parte incumple (FR-007/008/010).
  2. `if: github.actor == 'dependabot[bot]'`: mismos `types`, sin `subjectPattern` (FR-020).
- [X] T009 [US3] En `.github/rulesets/main-protection.json` añadir `{"context": "pr-title",
  "integration_id": 15368}` a `required_status_checks` (FR-011).
- [X] T010 [US3] Validar localmente: `actionlint .github/workflows/pr-title.yml` sin errores y JSON
  válido; si `actionlint` no está instalado, anotarlo y confiar en la ejecución del PR.
  (Nota: `actionlint` y Docker no están disponibles en esta máquina; YAML y JSON validados con
  Python, y el workflow se valida al correr en el PR.)

**Checkpoint**: El título se valida, se reevalúa al editarlo y es requerido por el ruleset.

---

## Phase 6: User Story 4 - Rama al día y squash merge como único método (Priority: P2)

**Goal**: Exigir rama actualizada, historial lineal y squash como único método de fusión.

**Independent Test**: Con dos PRs abiertos, tras fusionar el primero el segundo exige actualizarse; el
menú solo ofrece "Squash and merge" (quickstart paso 5).

### Implementation for User Story 4

- [X] T011 [US4] En `.github/rulesets/main-protection.json` fijar `allowed_merge_methods` `["squash"]`
  en la regla `pull_request` (FR-013), añadir la regla `required_linear_history` (FR-015) y poner
  `strict_required_status_checks_policy` en `true` (FR-012).

**Checkpoint**: El ruleset está completo y coincide con data-model.md y contracts/main-ruleset.md.

---

## Phase 7: User Story 5 - Reglas documentadas y reproducibles (Priority: P2)

**Goal**: Documentar la política y cómo aplicarla, restaurarla y auditarla.

**Independent Test**: Un colaborador nuevo abre y fusiona un PR válido siguiendo solo el README;
`diff` entre el ruleset activo y el versionado no muestra diferencias (quickstart paso 6).

### Implementation for User Story 5

- [X] T012 [US5] En `README.md`, sección `## Convenciones`, reconciliar el formato: mantener la línea
  de commits de rama `LAB-<n> type(scope): description` aclarando que no se validan porque se
  aplastan, y añadir la línea del título del PR `type(scope): [LAB-<n>] descripción` (ejemplo
  `feat(chat): [LAB-12] respuesta en streaming`) con los tipos permitidos `feat`, `fix`, `perf`,
  `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert` (`hotfix` usa `fix`).
- [X] T013 [US5] En `README.md`, bajo `## Integración continua`, reemplazar el bullet
  "Protección de rama" por una subsección `### Protección de main` que documente: flujo (rama → PR →
  checks → squash); lista de checks requeridos `ci-gate` y `pr-title` y qué cubre cada uno; reglas
  (sin push directo ni force push, sin bypass, rama al día, solo squash, historial lineal); cómo
  corregir un título inválido (editar el título, se reevalúa sin commit nuevo); el tratamiento de
  Dependabot (solo se valida el tipo); y que renombrar `ci-gate` o `pr-title` exige actualizar el
  ruleset en el mismo PR.
- [X] T014 [US5] En la misma subsección de `README.md` documentar cómo aplicar, restaurar y auditar,
  con los comandos de quickstart.md pasos 1 y 6: `gh api -X POST repos/leandroAmariles/shoplab-helpdesk/rulesets
  --input .github/rulesets/main-protection.json`, `PUT .../rulesets/<id>` si ya existe, el `PATCH` del
  repositorio con `allow_squash_merge=true`, `allow_merge_commit=false`, `allow_rebase_merge=false`,
  `delete_branch_on_merge=true`, `squash_merge_commit_title=PR_TITLE`,
  `squash_merge_commit_message=BLANK`, y el `diff` contra `gh api .../rulesets/<id>`.
  Dejar claro que el ruleset se activa tras fusionar la historia que introduce `pr-title`.

**Checkpoint**: README y definición versionada describen la misma política.

---

## Phase 8: Polish, Activation & Validation

**Purpose**: Verificar, fusionar, activar la protección y ejecutar los escenarios del quickstart.

- [X] T015 Revisión previa al commit: `git status --short` y `git diff --staged` solo con los
  archivos de este plan (sin `.orig`, logs ni temporales); JSON válido; `actionlint` limpio.
- [ ] T016 Abrir el PR de LAB-77 con el título
  `chore(ci): [LAB-77] protege main con checks requeridos` usando la plantilla; comprobar que
  `pr-title` y `ci-gate` quedan en verde (quickstart paso 0). Como aún no hay protección activa,
  verificar a mano que el título cumple el formato antes de fusionar con squash (el commit en
  `main` debe usar el título del PR).
- [ ] T017 Tras fusionar (y con LAB-75 y LAB-76 ya en `main`), activar: aplicar el ruleset y los
  ajustes del repositorio con los comandos de quickstart.md paso 1 (o `PUT` si ya existe) y guardar el
  id del ruleset.
- [ ] T018 Ejecutar el quickstart contra el repositorio real, anotando el resultado de cada paso:
  paso 2 (push directo y force push rechazados, también para el propietario), paso 3 (checks
  requeridos: fallo bloquea, pendiente bloquea, PR solo de README fusionable), paso 4 (títulos
  inválidos rojos, edición sin commit nuevo en < 2 min, PR de Dependabot verde), paso 5 (rama al día y
  solo squash; `git log --oneline main -3` sin merge commits).
- [ ] T019 Auditar con el `diff` del quickstart paso 6 que el ruleset activo y
  `.github/rulesets/main-protection.json` no difieren (SC-008) y marcar las tareas completadas y el
  estado de la spec.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 y T002 pueden ir en paralelo.
- **Foundational (Phase 2)**: depende de Setup (T002 fija el `integration_id`); bloquea las historias.
- **US1 (T004)**, **US2 (T005-T006)**, **US3 (T007-T010)** y **US4 (T011)**: editan en capas el mismo
  JSON, así que se ejecutan en orden de prioridad (US1 → US2 → US3 → US4). T007 y T008 (archivos
  distintos) pueden hacerse en paralelo entre sí y con T005/T006.
- **US5 (T012-T014)**: depende de que el contenido del ruleset esté cerrado (T011) para documentarlo.
- **Polish/Activation (Phase 8)**: T015-T016 antes de fusionar; T017-T019 solo después de fusionar.

### User Story Dependencies

- **US1 (P1)**: tras Foundational; MVP funcional (PR obligatorio, sin force push).
- **US2 (P1)**: tras US1 (mismo archivo); `ci-gate` ya existe.
- **US3 (P1)**: el workflow es independiente; la entrada en el ruleset (T009) va tras US2.
- **US4 (P2)**: tras US3 (mismo archivo).
- **US5 (P2)**: tras US4.

### Parallel Opportunities

- T001 y T002 (Setup).
- T007 (plantilla de PR) en paralelo con T005, T006 y T008 (archivos distintos).
- T012 (convenciones del README) podría adelantarse, pero comparte archivo con T013/T014, así que es
  secuencial.

---

## Parallel Example: User Story 3

```bash
# Archivos distintos, sin dependencias entre sí:
Task: "Actualizar .github/pull_request_template.md con el formato del título (T007)"
Task: "Crear .github/workflows/pr-title.yml con el job pr-title (T008)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Completar Phase 1 y Phase 2.
2. Completar Phase 3 (US1): ya se impide el push directo y el force push.
3. **Validar**: quickstart paso 2 tras aplicar el ruleset.

### Incremental Delivery

1. Setup + Foundational → esqueleto del ruleset.
2. US1 → US2 → US3 → US4 por capas sobre el mismo JSON, validando con `actionlint` y el JSON.
3. US5 documenta la política completa.
4. Fusionar, activar el ruleset y ejecutar el quickstart (Phase 8).

### Notes

- La protección solo existe tras T017; hasta entonces `main` sigue sin proteger (research.md,
  decisión 7), y T016 compensa verificando el título a mano.
- Cambiar el nombre de `ci-gate` o `pr-title` exige actualizar el JSON y reaplicarlo en el mismo PR.
- Commit tras cada tarea o grupo lógico, con el formato `LAB-77 type(scope): description`.
