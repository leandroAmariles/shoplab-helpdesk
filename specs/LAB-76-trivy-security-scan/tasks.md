---

description: "Task list for LAB-76 - Escaneo de vulnerabilidades y secretos con Trivy"
---

# Tasks: Escaneo de vulnerabilidades y secretos con Trivy

**Input**: Design documents from `/specs/LAB-76-trivy-security-scan/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/trivy-check.md, quickstart.md

**Tests**: No se pidieron tests de código. Las tareas de validación ejecutan los escenarios de
[quickstart.md](quickstart.md) contra el propio pipeline (`actionlint`, `trivy` local y PRs de
prueba).

**Organization**: Tareas agrupadas por historia de usuario. Casi todo el cambio vive en
`.github/workflows/ci.yml`, por eso las tareas que lo editan son secuenciales y no llevan `[P]`; solo
se marcan `[P]` las que tocan archivos distintos.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Puede ejecutarse en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: Historia de usuario a la que pertenece (US1..US4)

## Path Conventions

- Workflow: `.github/workflows/ci.yml` (job nuevo `trivy`, existente `ci-gate`)
- Excepciones: `.trivyignore` (raíz); Dependabot: `.github/dependabot.yml`
- Documentación del feature: `specs/LAB-76-trivy-security-scan/`
- Estilo: YAML con 2 espacios, comentarios solo donde el motivo no sea obvio; acciones existentes por
  tag mayor como en LAB-15 (`actions/checkout@v4`, `github/codeql-action/upload-sarif@v4`);
  `aquasecurity/trivy-action` fijada por SHA completo con el tag en un comentario
- Commits: `LAB-76 type(scope): description in english`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirmar los supuestos externos antes de escribir configuración que dependa de ellos.

- [X] T001 Obtener el último release publicado de `aquasecurity/trivy-action` y su SHA de commit
  completo con `gh api repos/aquasecurity/trivy-action/releases/latest --jq .tag_name` y
  `gh api repos/aquasecurity/trivy-action/git/ref/tags/<tag> --jq .object.sha` (si el objeto es un
  tag anotado, resolverlo al commit); anotar tag y SHA en
  `specs/LAB-76-trivy-security-scan/research.md` (decisión 7)
- [X] T002 Comprobar si Code Scanning está habilitado en el repo con
  `gh api repos/{owner}/{repo}/code-scanning/alerts`; si responde que no está habilitado, anotar la
  limitación en `specs/LAB-76-trivy-security-scan/research.md` (decisión 6): el SARIF quedará solo
  como artefacto

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Archivo que el job referencia y que debe existir antes de la primera ejecución.

**⚠️ CRITICAL**: El job `trivy` usa `trivyignores: .trivyignore`; sin el archivo el escaneo falla.

- [X] T003 Crear `.trivyignore` en la raíz del repo solo con el encabezado de comentarios `#`: para
  qué sirve, el formato de cada excepción (un id por línea precedido de comentarios con `Motivo:` y
  `Responsable: ... | Revisar antes de: AAAA-MM-DD`) y la regla de no añadir excepciones sin
  justificación (contrato en `specs/LAB-76-trivy-security-scan/contracts/trivy-check.md`); sin
  ninguna excepción inicial

**Checkpoint**: Archivo de excepciones listo; se puede añadir el job.

---

## Phase 3: User Story 1 - Bloqueo de vulnerabilidades y secretos graves en cada PR (Priority: P1) 🎯 MVP

**Goal**: Cada PR y cada push a `main` se escanea completo y falla si hay HIGH/CRITICAL con
corrección, secretos o misconfig graves.

**Independent Test**: PR limpio en verde; PR con dependencia vulnerable con corrección o secreto de
prueba en rojo (quickstart pasos 0–3).

### Implementation for User Story 1

- [X] T004 [US1] Añadir el job `trivy` a `.github/workflows/ci.yml` (sin `needs: changes` ni `if` por
  ruta; `runs-on: ubuntu-latest`, `timeout-minutes: 10`; `permissions: contents: read` y
  `security-events: write`) con `actions/checkout@v4` y el paso "Trivy report":
  `aquasecurity/trivy-action@<SHA de T001> # <tag>` con `scan-type: fs`, `scan-ref: .`,
  `scanners: vuln,secret,misconfig`, `severity: HIGH,CRITICAL`, `ignore-unfixed: true`,
  `skip-dirs: node_modules,target,.venv`, `trivyignores: .trivyignore`, `format: sarif`,
  `output: trivy-results.sarif`, `limit-severities-for-sarif: true`, `exit-code: '0'`
- [X] T005 [US1] Añadir en el job `trivy` de `.github/workflows/ci.yml` el paso "Trivy gate" con la
  misma acción y los mismos inputs de escaneo pero `format: table`, `exit-code: '1'` y sin `output`
  (el log debe listar paquete, versión instalada y versión corregida); no usar `--debug` ni imprimir
  el SARIF, para no exponer valores de secretos (FR-009)
- [X] T006 [US1] Añadir `trivy` a la lista `needs` del job `ci-gate` en `.github/workflows/ci.yml`
  (`[changes, backend, qodana, trivy, frontend, python]`) para que el único check exigido siga siendo
  `ci-gate`
- [X] T007 [US1] (actionlint no está instalado: se validó el YAML con PyYAML; trivy local limpio) Validar el workflow: `actionlint .github/workflows/ci.yml` sin errores y ensayo local
  `trivy fs . --scanners vuln,secret,misconfig --severity HIGH,CRITICAL --ignore-unfixed --skip-dirs
  node_modules,target,.venv --ignorefile .trivyignore --exit-code 1` (con el binario o con
  `docker run aquasec/trivy`); si hay hallazgos reales en el repo, corregirlos o registrarlos como
  excepciones justificadas en `.trivyignore` en lugar de debilitar el gate
- [ ] T008 [US1] Empujar la rama, abrir el PR y verificar el quickstart paso 1 (PR limpio: `trivy` y
  `ci-gate` en verde, incluso si el PR solo toca `.github/`); luego, en una rama de prueba descartable,
  verificar el paso 2 (dependencia con CVE CRITICAL corregido en `backend/pom.xml` → rojo con
  paquete/versión/corregida en el log) y el paso 3 (clave de ejemplo `AKIAIOSFODNN7EXAMPLE` → rojo,
  valor enmascarado); registrar los resultados en `specs/LAB-76-trivy-security-scan/quickstart.md`

**Checkpoint**: El gate de seguridad funciona de forma independiente (MVP).

---

## Phase 4: User Story 2 - Resultados visibles en la pestaña Security de GitHub (Priority: P2)

**Goal**: Los hallazgos se publican como SARIF aunque el gate falle; sin permisos de escritura se
conservan como artefacto.

**Independent Test**: Tras un escaneo con hallazgos, la pestaña Security muestra alertas de la
categoría `trivy` y existe el artefacto `trivy-report` (quickstart pasos 5 y 7).

### Implementation for User Story 2

- [X] T009 [US2] Definir en el job `trivy` de `.github/workflows/ci.yml` la variable de entorno
  `CAN_WRITE: ${{ github.event.pull_request.head.repo.fork != true && github.actor != 'dependabot[bot]' }}`
  con un comentario breve del motivo (tokens de solo lectura en forks y en PRs de Dependabot)
- [X] T010 [US2] Añadir en el job `trivy`, entre "Trivy report" y "Trivy gate", el paso
  `github/codeql-action/upload-sarif@v4` con `if: always() && env.CAN_WRITE == 'true'`,
  `sarif_file: trivy-results.sarif` y `category: trivy`
- [X] T011 [US2] Añadir al final del job `trivy` el paso `actions/upload-artifact@v4` con
  `if: always()`, `name: trivy-report`, `path: trivy-results.sarif`, `if-no-files-found: warn` y
  `retention-days: 14`; el paso "Trivy gate" debe ir antes de este para que su fallo no impida la
  subida
- [ ] T012 [US2] Verificar el quickstart pasos 5 y 7 sobre el PR de prueba de T008: alertas en
  Security > Code scanning con categoría `trivy`, artefacto `trivy-report` presente y, en un PR de
  fork o de Dependabot, subida omitida sin fallo; registrar el resultado en
  `specs/LAB-76-trivy-security-scan/quickstart.md`

**Checkpoint**: US1 y US2 funcionan; los hallazgos son visibles en GitHub.

---

## Phase 5: User Story 3 - Excepciones justificadas y versionadas con `.trivyignore` (Priority: P2)

**Goal**: Poder suprimir un hallazgo concreto de forma explícita, comentada y revisable.

**Independent Test**: Añadir una excepción con justificación hace pasar el check; quitarla lo vuelve
a poner en rojo (quickstart paso 4).

### Implementation for User Story 3

- [X] T013 [US3] (verificado en local con log4j-core 2.14.1 y un `--ignorefile` temporal) En la rama de prueba de T008, añadir al `.trivyignore` el id del hallazgo de
  prueba (CVE de la dependencia) precedido de comentarios `Motivo:` y `Responsable: ... | Revisar
  antes de: ...`, verificar que `trivy` pasa a verde y quitar la entrada para verificar que vuelve a
  rojo; no llevar esa entrada de prueba al PR de LAB-76
- [X] T014 [P] [US3] Añadir una sección "Seguridad: Trivy" a `README.md` (junto a la nota de Qodana de
  LAB-75 si ya existe): qué escanea, cuándo bloquea, cómo ver resultados en Security y cómo añadir una
  excepción siguiendo la convención del encabezado de `.trivyignore`

**Checkpoint**: Las excepciones son un mecanismo documentado y probado.

---

## Phase 6: User Story 4 - Actualizaciones automáticas con Dependabot (Priority: P2)

**Goal**: Dependabot abre PRs de actualización para todos los ecosistemas del proyecto.

**Independent Test**: La configuración cubre los cinco ecosistemas y, tras el merge, Dependabot aparece
activo y abre PRs (quickstart paso 6).

### Implementation for User Story 4

- [X] T015 [P] [US4] Crear `.github/dependabot.yml` (`version: 2`) con las entradas: `maven` en
  `/backend`, `npm` en `/frontend`, `pip` en `/ingest` y `/eval`, `github-actions` en `/`, `docker` en
  `/infra` y `docker-compose` en `/infra`; todas con `schedule.interval: weekly`,
  `open-pull-requests-limit: 5` y `commit-message.prefix: "chore(deps)"` (decisión 8 de research.md);
  comentar brevemente que `pip` no actualiza `uv.lock`
- [X] T016 [US4] Validar `.github/dependabot.yml`: que parsea como YAML
  (`python -c "import yaml,sys;yaml.safe_load(open('.github/dependabot.yml'))"`), que no hay
  ecosistemas duplicados para el mismo directorio y que los ecosistemas son los cinco pedidos más
  `docker-compose`
- [ ] T017 [US4] Tras el merge a `main`, verificar el quickstart paso 6 (Insights > Dependency graph >
  Dependabot: ecosistemas listados; avisos esperados por directorios sin manifiesto) y registrar el
  resultado en `specs/LAB-76-trivy-security-scan/quickstart.md`

**Checkpoint**: Las cuatro historias funcionan de forma independiente.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Verificación final y trazabilidad.

- [ ] T018 Recorrer los 7 pasos de `specs/LAB-76-trivy-security-scan/quickstart.md` contra el PR y
  dejar constancia de cada resultado (verde/rojo esperado) en ese archivo
- [ ] T019 Anotar en la descripción del PR que el job `qodana` de LAB-75 solo excluye forks y podría
  fallar en PRs de Dependabot por el token de solo lectura (research.md, decisión 6), sin modificarlo
  aquí
- [X] T020 Antes de commitear: `git status --short` para descartar archivos sueltos
  (`trivy-results.sarif`, ramas de prueba), `git diff --staged` para confirmar que solo están
  `ci.yml`, `.trivyignore`, `.github/dependabot.yml`, `README.md` y la carpeta `specs/LAB-76-...`
- [X] T021 Como el cambio no añade ni modifica endpoints HTTP ni propiedades de configuración, no
  generar `requests.http` ni lista de propiedades nuevas; indicarlo en el resumen final

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias; T001 es necesaria para T004
- **Foundational (Phase 2)**: T003 bloquea T004 en adelante
- **US1 (Phase 3)**: depende de Phase 2; es el MVP
- **US2 (Phase 4)**: depende de US1 (edita el mismo job)
- **US3 (Phase 5)**: T013 depende de T008 (necesita el PR/rama de prueba); T014 es independiente
- **US4 (Phase 6)**: independiente de US1–US3 (archivo propio)
- **Polish (Phase 7)**: depende de las historias deseadas

### Within Each User Story

- En `ci.yml`: T004 → T005 → T006 (US1), T009 → T010 → T011 (US2); no son paralelizables
- Validar (actionlint/local) antes de empujar; verificar en GitHub al final de cada historia

### Parallel Opportunities

- T014 (README) y T015 (`dependabot.yml`) tocan archivos distintos y pueden hacerse en paralelo
  entre sí y con las tareas de `ci.yml`
- US4 completa puede hacerse en paralelo con US1/US2

---

## Parallel Example

```text
# En paralelo (archivos distintos):
Task: "T015 Crear .github/dependabot.yml"
Task: "T014 Añadir sección Trivy al README.md"
# Mientras en serie sobre .github/workflows/ci.yml: T004 → T005 → T006 → T009 → T010 → T011
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Setup (T001–T002) y Foundational (T003)
2. US1 (T004–T008): el gate bloquea; **STOP y validar** con el PR limpio y los PRs de prueba
3. Mergeable ya como incremento útil

### Incremental Delivery

1. US1 → gate activo
2. US2 → resultados en Security y artefacto
3. US3 → excepciones probadas y documentadas
4. US4 → Dependabot
5. Polish → quickstart completo, PR

---

## Notes

- [P] tasks = archivos distintos, sin dependencias
- Las ramas y archivos de prueba (dependencia vulnerable, clave de ejemplo) nunca se llevan al PR
- Solo se endurece `trivy-action` con SHA; las demás acciones mantienen la convención de LAB-15
- Commit tras cada tarea o grupo lógico, con prefijo `LAB-76`
