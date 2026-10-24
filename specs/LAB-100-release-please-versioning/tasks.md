---

description: "Task list for LAB-100 - Versionado semántico automático con release-please"
---

# Tasks: Versionado semántico automático con release-please

**Input**: Design documents from `/specs/LAB-100-release-please-versioning/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/release-please-config.md,
contracts/release-flow.md, quickstart.md

**Tests**: No se pidieron tests de código. La validación ejecuta los escenarios de
[quickstart.md](quickstart.md) (`actionlint`, validación del JSON y PRs reales en GitHub).

**Organization**: Tareas agrupadas por historia de usuario. Las historias 1, 2 y 3 comparten dos
archivos (`release-please-config.json` y `.github/workflows/release-please.yml`), así que sus tareas
sobre esos archivos son secuenciales y no llevan `[P]`. Los escenarios de las historias 1, 2 y 5 solo
se pueden comprobar **después de fusionar** esta rama en `main` (release-please corre con el push a
`main`), por eso su verificación vive en las fases finales.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US5)

## Path Conventions

- Workflow nuevo: `.github/workflows/release-please.yml`; `ci.yml` y `pr-title.yml` no se modifican
- Configuración: `release-please-config.json` y `.release-please-manifest.json` en la raíz
- `CHANGELOG.md` lo crea release-please; no se escribe a mano
- Documentación: `README.md`, `CLAUDE.md`, `.ai/rules/development-guidelines.md`
- Documentación del feature: `specs/LAB-100-release-please-versioning/`
- Estilo: YAML con 2 espacios; acciones de terceros fijadas por SHA completo con el tag en un
  comentario (como `pr-title.yml`); JSON con 2 espacios; sin secretos en el repositorio
- Título del PR: `type(scope): [LAB-100] descripción` (los commits de rama no se validan)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirmar los supuestos externos antes de escribir configuración que dependa de ellos.

- [X] T001 Obtener el último release v4.x de `googleapis/release-please-action` y su SHA de commit
  completo con `gh api repos/googleapis/release-please-action/releases --jq '[.[] | select(.tag_name
  | startswith("v4"))][0].tag_name'` y `gh api repos/googleapis/release-please-action/git/ref/tags/<tag>
  --jq .object.sha` (si el objeto es un tag anotado, resolver al commit con `git/tags/<sha>`); anotar
  tag y SHA para T007.
- [X] T002 Confirmar los prerrequisitos de plataforma y anotar el resultado en
  `specs/LAB-100-release-please-versioning/research.md` (nueva sección 10, "Prerrequisitos del
  repositorio"): `gh auth status` con permisos de administrador sobre
  `leandroAmariles/shoplab-helpdesk`; que no existen tags ni releases previos
  (`git tag --list` y `gh release list`); que `ci.yml` expone `ci-gate` y `pr-title.yml` expone
  `pr-title` con esos nombres exactos; y que el ruleset `main-protect` está activo.
- [ ] T003 Crear el secreto de repositorio `RELEASE_PLEASE_TOKEN` (token de acceso personal de grano
  fino, solo este repositorio, `Contents` y `Pull requests` en lectura/escritura, caducidad anotada) y
  habilitar "Allow GitHub Actions to create and approve pull requests" en *Settings → Actions →
  General*. Acción manual del propietario; verificar con `gh secret list` y
  `gh api repos/leandroAmariles/shoplab-helpdesk/actions/permissions/workflow --jq
  .can_approve_pull_request_reviews`. No guardar el valor del token en ningún archivo.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuración base del manifiesto sobre la que las historias 1, 2, 3 y 5 se apoyan.

**⚠️ CRITICAL**: Ninguna historia puede completarse hasta que existan estos dos archivos.

- [X] T004 Crear `.release-please-manifest.json` en la raíz con `{ ".": "0.0.0" }` (data-model.md,
  "Manifiesto de release": semver `X.Y.Z`, inicial `0.0.0`).
- [X] T005 Crear `release-please-config.json` en la raíz con el contenido de
  [contracts/release-please-config.md](contracts/release-please-config.md): `include-component-in-tag:
  false`, `bump-minor-pre-major: true`, `bump-patch-for-minor-pre-major: false`, `release-as:
  "0.1.0"` (temporal), `pull-request-title-pattern: "chore${scope}: [LAB-100] release ${version}"`,
  `changelog-sections` (visibles `feat`, `fix`, `perf`, `revert`; ocultos `docs`, `refactor`, `test`,
  `build`, `ci`, `chore`) y el paquete raíz `.` con `release-type: simple` y `changelog-path:
  CHANGELOG.md`. Sin `extra-files` todavía (lo añaden T012 y T013).

**Checkpoint**: Ambos archivos son JSON válido y coinciden con los contratos.

---

## Phase 3: User Story 1 - Un PR de release se mantiene solo a partir de los commits (Priority: P1) 🎯 MVP

**Goal**: Cada push a `main` abre o actualiza como máximo un PR de release con versión y changelog,
sin pasos manuales y con permisos mínimos.

**Independent Test**: Tras fusionar un cambio `feat`, aparece un único PR `chore(main): [LAB-100] release
0.1.0` con `CHANGELOG.md` actualizado y con `ci-gate` y `pr-title` en verde (quickstart §1).

### Implementation for User Story 1

- [X] T006 [US1] Crear `.github/workflows/release-please.yml`: `name: Release Please`; `on: push:
  branches: [main]`; `permissions: contents: write, pull-requests: write, issues: write`;
  `concurrency: group: release-please, cancel-in-progress: false`; un job `release-please` con
  `runs-on: ubuntu-latest` y `timeout-minutes: 10` (contrato "Workflow
  `.github/workflows/release-please.yml`").
- [X] T007 [US1] En `.github/workflows/release-please.yml`, añadir el paso `uses:
  googleapis/release-please-action@<SHA de T001> # v4.x.y` con `token: ${{
  secrets.RELEASE_PLEASE_TOKEN }}`, `config-file: release-please-config.json` y `manifest-file:
  .release-please-manifest.json`. Sin otros pasos ni checkout.
- [X] T008 [US1] Validar en local: `actionlint .github/workflows/release-please.yml` y
  `python -c "import json; [json.load(open(f)) for f in ('release-please-config.json',
  '.release-please-manifest.json')]"`; corregir lo que reporten (quickstart §0).

**Checkpoint**: El workflow y la configuración son válidos; la historia se comprueba en vivo en T022.

---

## Phase 4: User Story 3 - Un paquete por componente con una versión coherente (Priority: P1)

**Goal**: La versión de cada componente (backend Maven, frontend npm, ingest Python) se actualiza
junto al manifiesto bajo un único tag común.

**Independent Test**: El diff del PR de release cambia `backend/pom.xml` a la versión del release sin
tocar el `<parent>`, y `ci-gate` pasa (quickstart §1).

### Implementation for User Story 3

- [ ] T009 [US3] (pendiente: requiere rama de prueba empujada; no bloquea, ver research §1.1) Verificar el comportamiento de `extra-files` con archivos inexistentes (research.md
  §1.1) en una rama de prueba desechable: añadir las entradas de `frontend/package.json` y
  `ingest/pyproject.toml` sin crear los archivos, ejecutar release-please en local
  (`npx release-please release-pr --dry-run` con `--token` de lectura) o en un fork, y anotar el
  resultado (tolera / falla) en `specs/LAB-100-release-please-versioning/research.md` §1.1.
- [X] T010 [US3] En `release-please-config.json`, añadir a `packages["."].extra-files` la entrada
  `{ "type": "xml", "path": "backend/pom.xml", "xpath":
  "//*[local-name()='project']/*[local-name()='version']" }`. Debe apuntar al `<version>` hijo directo
  de `<project>`, nunca al del `<parent>` (Spring Boot 4.1.2); la versión actual es
  `0.0.1-SNAPSHOT`.
- [X] T011 [US3] Comprobar localmente que el XPath de T010 selecciona un único nodo y no el del
  `<parent>`: `python -c "import xml.etree.ElementTree as E; n={'m':'http://maven.apache.org/POM/4.0.0'};
  print([e.text for e in E.parse('backend/pom.xml').getroot().findall('m:version', n)])"` debe
  imprimir solo `['0.0.1-SNAPSHOT']`.
- [X] T012 [US3] Según el resultado de T009: si release-please tolera archivos ausentes, añadir a
  `extra-files` `{ "type": "json", "path": "frontend/package.json", "jsonpath": "$.version" }` y
  `{ "type": "toml", "path": "ingest/pyproject.toml", "jsonpath": "$.project.version" }`; si falla,
  no añadirlas y dejar en `specs/LAB-100-release-please-versioning/research.md` §1 la instrucción de
  añadirlas cuando cada proyecto exista. No crear `package.json` ni `pyproject.toml`: `ci.yml` los
  detectaría como proyectos y fallaría en `npm ci` / `uv sync --frozen`.
- [X] T013 [US3] Revalidar el JSON de `release-please-config.json` (T008) tras los cambios de T010 y
  T012.

**Checkpoint**: Las versiones de los componentes existentes se actualizan bajo un único tag común.

---

## Phase 5: User Story 4 - Convención de commits y títulos documentada (Priority: P2)

**Goal**: README, `CLAUDE.md` y las guías del equipo explican cómo cada tipo afecta a la versión, sin
contradicciones con LAB-77.

**Independent Test**: Un colaborador redacta, solo con el README, un título de PR que pasa
`pr-title` y genera la entrada de changelog esperada; los tres documentos muestran la misma tabla y
el mismo ejemplo (quickstart §4).

### Implementation for User Story 4

- [X] T014 [P] [US4] En `README.md`, dentro de "Convenciones", añadir una subsección "Versionado y
  releases" con: la tabla tipo → versión (`feat` → minor, `fix`/`perf` → patch, `!` o `BREAKING
  CHANGE` → major, y minor mientras sea `0.x`; `docs`, `chore`, `test`, `ci`, `build`, `refactor`
  no generan release); la aclaración de que **solo el título del PR** (commit de squash) determina la
  versión; el ejemplo `feat(chat): [LAB-12] respuesta en streaming`; el flujo del PR de release
  `chore(main): [LAB-100] release <versión>`, el tag `vX.Y.Z` y el GitHub Release; y el procedimiento de
  rotación del `RELEASE_PLEASE_TOKEN` con su fecha de caducidad. Reconciliar con la línea existente
  de "Título del PR" sin contradecirla.
- [X] T015 [P] [US4] En `.ai/rules/development-guidelines.md` §5 (Commits), añadir la misma tabla
  tipo → versión y el ejemplo con clave LAB, y aclarar que el formato `type(scope): description`
  aplica a commits de rama mientras que el título del PR es `type(scope): [LAB-<n>] descripción`.
- [X] T016 [US4] Replicar el cambio de T015 en `CLAUDE.md` §5 (Commits), con el texto idéntico, ya
  que `CLAUDE.md` consolida las reglas de `.ai/rules/`.
- [X] T017 [US4] Revisar que `README.md`, `CLAUDE.md` y `.ai/rules/development-guidelines.md` no se
  contradicen entre sí ni con `.github/workflows/pr-title.yml` (tipos permitidos y patrón
  `^\[LAB-\d+\] .+$`), y que el título del PR de release del contrato cumple ese patrón.

**Checkpoint**: Documentación consistente (SC-008).

---

## Phase 6: Cierre de esta rama y fusión

**Purpose**: Dejar la rama lista para que el push a `main` ponga en marcha release-please.

- [X] T018 Revisar `git status --short` y `git diff` (solo cambios intencionales: workflow, dos JSON,
  documentación y `specs/LAB-100-release-please-versioning/`); no commitear `CHANGELOG.md` ni
  modificar `backend/pom.xml` a mano.
- [X] T019 Abrir el PR con título `chore(ci): [LAB-100] automatiza versiones con release-please` y
  comprobar `ci-gate` y `pr-title` en verde. Fusionar por squash.

---

## Phase 7: User Story 2 - Fusionar el PR de release publica tag y GitHub Release (Priority: P1)

**Goal**: Fusionar el PR de release crea `vX.Y.Z` y el GitHub Release con las notas, sin duplicados y
con capacidad de disparar el workflow de LAB-78.

**Independent Test**: Tras fusionar el PR de release existen el tag y el Release no borrador, y
repetir la ejecución del workflow no crea otros (quickstart §3).

### Verification for User Story 2 (post-merge)

- [X] T020 [US2] Con el PR de LAB-100 ya fusionado, comprobar en < 5 min que el workflow corrió:
  `gh run list --workflow release-please.yml --limit 1` en verde. Si falla por permisos o token,
  corregir `RELEASE_PLEASE_TOKEN` (T003) y relanzar con `gh run rerun`.
- [X] T021 [US1] Verificar que existe un único PR de release con título `chore(main): [LAB-100] release
  0.1.0`, etiqueta `autorelease: pending` y diff en `CHANGELOG.md`, `.release-please-manifest.json`
  (`0.1.0`) y `backend/pom.xml` (versión `0.1.0`, `<parent>` intacto):
  `gh pr list --head release-please--branches--main --json number,title,labels` (quickstart §1).
- [X] T022 [US1] Comprobar que `ci-gate` y `pr-title` se ejecutan y pasan sobre el PR de release
  (`gh pr checks <n>`), lo que confirma que el token dispara workflows. Si no se ejecutan,
  revisar el token (research §2).
- [X] T023 [US2] Fusionar el PR de release (squash) y comprobar: `git fetch --tags && git tag --list
  'v*'` muestra `v0.1.0`; `gh release view v0.1.0 --json tagName,isDraft,body` devuelve un Release no
  borrador cuyas notas coinciden con la entrada de `CHANGELOG.md`; el tag se creó sobre el commit de
  la fusión (`git rev-parse v0.1.0` frente a `git log -1 --format=%H`).
- [X] T024 [US2] Probar idempotencia: `gh run rerun` del workflow de release sobre el último push y
  comprobar que no aparece ningún tag, Release ni PR de release adicional.
- [ ] T025 [US2] (pendiente de LAB-78: el workflow de imagen aún no existe) Si el workflow de imagen de LAB-78 ya existe, comprobar que arrancó con el tag
  `v0.1.0`; si no, dejar anotado en `specs/LAB-100-release-please-versioning/quickstart.md` que esa
  comprobación queda pendiente de LAB-78.

**Checkpoint**: El contrato de [release-flow.md](contracts/release-flow.md) se cumple de extremo a
extremo.

---

## Phase 8: User Story 5 - Primer release `v0.1.0` verificado (Priority: P2)

**Goal**: Confirmar que el mecanismo sigue solo tras el primer release y retirar la versión forzada.

**Independent Test**: Tras `v0.1.0`, un `fix` posterior propone `v0.1.1` (quickstart §2 y §3).

### Implementation for User Story 5

- [X] T026 [US5] Abrir un PR `chore(ci): [LAB-100] retira release-as` que elimine `release-as` de
  `release-please-config.json`; comprobar `ci-gate` y `pr-title` en verde y fusionar por squash.
  Debe hacerse después de T023 (si no, la versión seguiría forzada a `0.1.0`).
- [X] T027 [US5] Revisar `CHANGELOG.md` en `main`: contiene la entrada de `v0.1.0` agrupada por tipo
  y con enlaces a los commits (SC-006).
- [X] T028 [US5] Fusionar un cambio mínimo `fix: [LAB-100] ...` (p. ej. una errata de documentación
  con tipo `fix`) y comprobar que el siguiente PR de release propone `0.1.1`; después un `docs` o
  `chore` solo no debe abrir otro (quickstart §2). Cerrar o fusionar el PR de release de prueba
  según convenga.

**Checkpoint**: Reglas de versión (`feat`/`fix`/`!`) comprobadas con `0.1.1`; el resto se valida por
inspección de la configuración (`bump-minor-pre-major`).

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Cierre de la historia.

- [X] T029 [P] Anotar en `specs/LAB-100-release-please-versioning/quickstart.md` los resultados
  reales (PR, run, tag, release) y la fecha de caducidad del `RELEASE_PLEASE_TOKEN`; actualizar
  `research.md` §1.1 con el resultado de T009.
- [ ] T030 (parcial: §0 sin `actionlint` y §5/T009 sin verificar) Ejecutar el checklist del quickstart completo (§0 a §5) y marcar en
  `checklists/requirements.md` cualquier ítem que haya cambiado.
- [X] T031 Informar en el resumen final: el estado de cada criterio de aceptación de LAB-100, la
  desviación del paquete raíz frente a "un paquete por componente" (research §1) y el resultado de
  T009.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias. T003 (secreto) puede hacerse en cualquier momento antes de
  fusionar, pero es necesario antes de T020.
- **Foundational (Phase 2)**: depende de T001–T002; bloquea todas las historias.
- **US1 (Phase 3)**: depende de Foundational; T006–T008 son secuenciales (mismo archivo).
- **US3 (Phase 4)**: depende de Foundational; T010–T013 editan `release-please-config.json`, así que
  son secuenciales; T009 puede ir en paralelo con US1.
- **US4 (Phase 5)**: independiente del código; T014 y T015 en paralelo; T016 sigue a T015; T017 al
  final.
- **Cierre (Phase 6)**: depende de las Phases 3, 4 y 5.
- **US2 (Phase 7)** y **US5 (Phase 8)**: dependen de la fusión (T019); US5 depende de T023.
- **Polish (Phase 9)**: al final.

### User Story Dependencies

- **US1 (P1)**: tras Foundational. Se verifica tras la fusión (T021–T022).
- **US3 (P1)**: tras Foundational; comparte `release-please-config.json` con Foundational.
- **US4 (P2)**: independiente del resto.
- **US2 (P1)**: tras la fusión de la rama y el PR de release.
- **US5 (P2)**: tras US2.

### Parallel Opportunities

- T009 (verificación en rama de prueba) en paralelo con T006–T008.
- T014 y T015 (README y guías) en paralelo, y en paralelo con US1/US3 (archivos distintos).
- T029 en paralelo con T030.

---

## Parallel Example: documentación (US4)

```bash
# Archivos distintos, sin dependencias entre sí:
Task: "T014 Subsección 'Versionado y releases' en README.md"
Task: "T015 §5 de .ai/rules/development-guidelines.md"
```

---

## Implementation Strategy

### MVP First (User Story 1 + 3)

1. Phase 1 (Setup) y Phase 2 (Foundational).
2. Phase 3 (US1) y Phase 4 (US3): workflow + configuración con `backend/pom.xml`.
3. **STOP y VALIDAR** en local (T008, T011, T013).
4. Phase 5 (docs), Phase 6 (fusión).
5. Phase 7: comprobar el PR de release y fusionarlo (T020–T025).

### Incremental Delivery

1. Workflow y configuración fusionados → aparece el PR de release (US1).
2. Fusión del PR de release → `v0.1.0`, tag y Release (US2, US5).
3. Retirar `release-as` y confirmar `v0.1.1` con un `fix` (US5).
4. Documentación (US4) viaja en el mismo PR que el workflow.

---

## Notes

- [P] = archivos distintos, sin dependencias
- [Story] mapea cada tarea a su historia; las fases de verificación posteriores a la fusión
  conservan la etiqueta de la historia que comprueban
- T003 es una acción manual del propietario: ningún valor de token se escribe en el repositorio
- No modificar `ci.yml`, `pr-title.yml` ni `.github/rulesets/main-protection.json`
- No crear `frontend/package.json` ni `ingest/pyproject.toml` en esta historia
