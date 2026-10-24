# Feature Specification: Versionado semántico automático con release-please

**Feature Branch**: `chore/LAB-100-release-please-versioning`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-100 (F0-13) Como desarrollador, quiero que las versiones y el changelog salgan solos de los commits, para publicar releases sin pasos manuales. Criterios: `googleapis/release-please-action@v4` en cada push a `main`, en modo manifest (`release-please-config.json` y `.release-please-manifest.json`) con un paquete por componente: backend (Maven), frontend (npm) e ingest (Python); mantiene abierto un PR de release que sube la versión y actualiza el `CHANGELOG.md` a partir de los Conventional Commits (`feat` sube la minor, `fix` la patch, `!` la major); al fusionar ese PR crea el tag `vX.Y.Z` y el GitHub Release, que disparan la imagen versionada de LAB-78; la convención de commits y títulos de PR con la clave LAB queda documentada en el README y en `CLAUDE.md`; probado con un primer release `v0.1.0`. No necesita nada local; todo corre en GitHub Actions. Depende de: LAB-15."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Un PR de release se mantiene solo a partir de los commits (Priority: P1)

Como desarrollador, quiero que cada vez que se integra un cambio en `main` se abra o actualice
automáticamente un pull request de release con la nueva versión y el changelog, para no calcular
versiones ni escribir notas de versión a mano.

**Why this priority**: Es el núcleo de la historia: sin este PR automático no hay versionado ni
changelog y todo el resto (tag, release, imagen) queda sin disparador.

**Independent Test**: Fusionar en `main` un cambio `feat(...)` y comprobar que aparece (o se
actualiza) un único PR de release que sube la versión y añade la entrada al `CHANGELOG.md`.

**Acceptance Scenarios**:

1. **Given** un commit `feat` fusionado en `main` desde el último release, **When** corre el
   workflow, **Then** existe un PR de release abierto que sube la versión minor y añade al
   `CHANGELOG.md` una entrada en la sección de funcionalidades.
2. **Given** solo commits `fix` desde el último release, **When** corre el workflow, **Then** el PR
   de release sube la versión patch.
3. **Given** un commit con `!` tras el tipo (p. ej. `feat(api)!: ...`) o un cuerpo
   `BREAKING CHANGE:`, **When** corre el workflow, **Then** el PR de release sube la versión major
   (mientras la versión sea `0.x`, ver Assumptions).
4. **Given** un PR de release ya abierto, **When** se fusiona un nuevo cambio en `main`, **Then** el
   mismo PR se actualiza (versión y changelog) en lugar de abrirse otro.
5. **Given** solo commits que no afectan a la versión (p. ej. `chore`, `docs`, `test`), **When**
   corre el workflow, **Then** no se abre ningún PR de release.
6. **Given** un push a `main`, **When** el workflow termina, **Then** lo hace con los permisos
   mínimos necesarios y sin pasos manuales.

---

### User Story 2 - Fusionar el PR de release publica tag y GitHub Release (Priority: P1)

Como desarrollador, quiero que al fusionar el PR de release se cree automáticamente el tag
`vX.Y.Z` y el GitHub Release con sus notas, para publicar sin pasos manuales y para que la imagen
versionada de LAB-78 se dispare sola.

**Why this priority**: Es lo que convierte el PR en una release real y conecta con el pipeline de
imágenes; sin tag no hay artefacto versionado.

**Independent Test**: Fusionar un PR de release y comprobar que existen el tag `vX.Y.Z` y un
GitHub Release con las notas del changelog, y que el workflow de imagen versionada de LAB-78 se
inicia con ese tag.

**Acceptance Scenarios**:

1. **Given** un PR de release abierto, **When** se fusiona, **Then** se crea el tag `vX.Y.Z` con la
   versión del PR sobre el commit de la fusión.
2. **Given** el tag creado, **When** se revisa la página de releases, **Then** existe un GitHub
   Release `vX.Y.Z` cuyas notas coinciden con la entrada del `CHANGELOG.md`.
3. **Given** el tag o el GitHub Release creados, **When** se publican, **Then** son capaces de
   disparar el workflow de imagen versionada de LAB-78.
4. **Given** una fusión de PR de release ya procesada, **When** el workflow se vuelve a ejecutar
   (re-run o push posterior), **Then** no se crea un segundo tag ni un segundo release para la misma
   versión.

---

### User Story 3 - Un paquete por componente con una versión coherente (Priority: P1)

Como desarrollador, quiero que backend (Maven), frontend (npm) e ingest (Python) estén registrados
como paquetes del release, para que sus versiones y el changelog se actualicen juntos y reflejen
el mismo release.

**Why this priority**: El repositorio es un monorepo; sin registrar los tres componentes, la
versión publicada no reflejaría todo el sistema.

**Independent Test**: Revisar el PR de release y comprobar que actualiza el archivo de versión de
cada componente registrado y que la versión del tag coincide con la de todos ellos.

**Acceptance Scenarios**:

1. **Given** el manifiesto del repositorio, **When** se revisa, **Then** lista un paquete por cada
   componente: backend, frontend e ingest, cada uno con su tipo de release (Maven, npm, Python).
2. **Given** un PR de release, **When** se revisan sus archivos, **Then** la versión se actualiza en
   el archivo de versión nativo de cada componente afectado y en el manifiesto.
3. **Given** un cambio que toca solo un componente, **When** se calcula el release, **Then** la
   entrada del changelog refleja ese cambio sin romper la versión del tag común.
4. **Given** el componente backend, **When** se actualiza su versión, **Then** deja de ser
   `SNAPSHOT` en el commit del release y el build sigue pasando.

---

### User Story 4 - Convención de commits y títulos documentada (Priority: P2)

Como desarrollador, quiero que la convención de Conventional Commits y de títulos de PR con la
clave LAB esté documentada en el README y en `CLAUDE.md`, para que cualquier colaborador o agente
escriba mensajes que el versionado automático pueda leer.

**Why this priority**: El versionado automático solo es tan bueno como los mensajes que lee; sin
documentación se generan commits ilegibles, pero el mecanismo funciona sin ella.

**Independent Test**: Leer el README y `CLAUDE.md` y, siguiéndolos, redactar un título de PR que
pase la validación y produzca la entrada de changelog esperada.

**Acceptance Scenarios**:

1. **Given** el README, **When** se consulta, **Then** explica cómo cada tipo de commit afecta a la
   versión (`feat` → minor, `fix` → patch, `!` → major), el formato del título con ejemplo
   `feat(chat): [LAB-12] ...` y el flujo del PR de release.
2. **Given** `CLAUDE.md`, **When** se consulta, **Then** contiene la misma convención, sin
   contradecir el README.
3. **Given** la documentación existente de commits y títulos, **When** se actualiza, **Then** no
   queda ninguna regla contradictoria entre README, `CLAUDE.md` y la validación de título vigente.

---

### User Story 5 - Primer release `v0.1.0` verificado (Priority: P2)

Como desarrollador, quiero comprobar el mecanismo extremo a extremo con un primer release
`v0.1.0`, para confiar en que los siguientes releases saldrán solos.

**Why this priority**: Es la prueba de aceptación de la historia, pero depende de que las
anteriores funcionen.

**Independent Test**: Seguir el flujo completo (commits → PR de release → fusión) y comprobar que
se publica `v0.1.0` con tag, GitHub Release, changelog y disparo del workflow de imagen.

**Acceptance Scenarios**:

1. **Given** el repositorio sin releases previos, **When** se fusiona el primer PR de release,
   **Then** se publica `v0.1.0` con tag y GitHub Release.
2. **Given** el `CHANGELOG.md` tras `v0.1.0`, **When** se revisa, **Then** contiene la entrada de
   `v0.1.0` agrupada por tipo y con enlaces a los commits.
3. **Given** `v0.1.0` publicado, **When** se hace un nuevo cambio `fix`, **Then** el siguiente PR
   de release propone `v0.1.1`.

---

### Edge Cases

- ¿Qué pasa con los commits previos al primer release que no siguen Conventional Commits? Se
  ignoran para el cálculo de versión sin hacer fallar el workflow.
- ¿Qué pasa con `frontend` e `ingest` mientras sean carpetas vacías (hoy solo `.gitkeep`)? El
  registro del paquete no debe romper el workflow; deben poder versionarse cuando exista su archivo
  de versión (ver Assumptions).
- ¿Qué pasa si el PR de release falla los checks requeridos de `main` (LAB-77)? Debe ejecutarse la
  CI sobre él como sobre cualquier otro PR y cumplir las mismas reglas de fusión, sin bypass.
- ¿Qué pasa si los PRs creados con el token por defecto no disparan la CI? El PR de release debe
  poder pasar los checks requeridos; la forma de lograrlo queda para el plan.
- ¿Qué pasa si dos pushes a `main` ocurren casi a la vez? El workflow no debe crear PRs de release
  duplicados ni tags repetidos.
- ¿Qué pasa si el título de un PR (commit de squash) no es válido? La validación de título
  (LAB-77) ya lo rechaza; aquí se garantiza que un commit ilegible no genere una versión errónea.
- ¿Qué pasa si se edita manualmente el PR de release (p. ej. el texto del changelog)? Las
  ediciones manuales no deben perderse silenciosamente al siguiente push, o su tratamiento debe
  quedar documentado.
- ¿Qué pasa si el tag `vX.Y.Z` ya existe? El workflow no lo sobrescribe y reporta el conflicto.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST ejecutar `googleapis/release-please-action@v4` en cada push a
  `main`.
- **FR-002**: release-please MUST configurarse en modo manifest, con `release-please-config.json` y
  `.release-please-manifest.json` versionados en la raíz.
- **FR-003**: El manifiesto MUST registrar un paquete por componente: backend (Maven), frontend
  (npm) e ingest (Python), cada uno con su tipo de release correspondiente.
- **FR-004**: El workflow MUST mantener abierto, como máximo, un PR de release que suba la versión
  y actualice `CHANGELOG.md` a partir de los Conventional Commits desde el último release.
- **FR-005**: El cálculo de versión MUST seguir Conventional Commits: `feat` sube la minor, `fix`
  la patch y un cambio con `!` o `BREAKING CHANGE:` la major.
- **FR-006**: Los commits que no afectan a la versión (`chore`, `docs`, `test`, `ci`, etc.) MUST NOT
  abrir por sí solos un PR de release.
- **FR-007**: Al fusionar el PR de release, el workflow MUST crear el tag `vX.Y.Z` y el GitHub
  Release con las notas del changelog.
- **FR-008**: El tag o el GitHub Release creados MUST poder disparar el workflow de imagen
  versionada de LAB-78.
- **FR-009**: El workflow MUST ser idempotente: repetir su ejecución no MUST crear PRs, tags ni
  releases duplicados.
- **FR-010**: El PR de release MUST someterse a los mismos checks requeridos y reglas de fusión de
  `main` (LAB-77), sin bypass.
- **FR-011**: El workflow MUST usar permisos mínimos y no MUST exponer secretos adicionales.
- **FR-012**: La convención de Conventional Commits y de títulos de PR con la clave LAB (p. ej.
  `feat(chat): [LAB-12] ...`) MUST estar documentada en el README y en `CLAUDE.md`, sin
  contradicciones entre ambos ni con la validación de título vigente.
- **FR-013**: El primer release MUST ser `v0.1.0` y MUST verificarse extremo a extremo (PR de
  release, fusión, tag, GitHub Release, changelog).
- **FR-014**: La historia MUST NOT modificar el comportamiento de los gates existentes (LAB-15,
  LAB-75, LAB-76, LAB-77) ni implementar la construcción de la imagen (LAB-78).
- **FR-015**: Todo MUST ejecutarse en GitHub Actions; no se requiere ninguna herramienta ni paso
  local para publicar un release.

### Key Entities

- **Manifiesto de release**: configuración y registro de versiones actuales por paquete que
  determina qué componentes se versionan y cómo.
- **Paquete (componente)**: unidad versionable del monorepo (backend, frontend, ingest) con su
  archivo de versión nativo.
- **PR de release**: pull request mantenido automáticamente que contiene la nueva versión y el
  changelog; al fusionarse publica el release.
- **Changelog**: `CHANGELOG.md` agrupado por tipo de cambio, generado desde los commits.
- **Tag / GitHub Release `vX.Y.Z`**: referencia inmutable de una versión publicada y su página de
  notas; dispara la imagen versionada.
- **Convención de commits y títulos**: contrato `type(scope)!: [LAB-<n>] descripción` del que
  depende el cálculo de versión.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Tras fusionar un cambio que afecta a la versión, el PR de release aparece o se
  actualiza en menos de 5 minutos sin intervención manual.
- **SC-002**: El 100 % de los releases publicados desde la activación se generan sin pasos manuales
  de versionado ni redacción de changelog.
- **SC-003**: La versión propuesta coincide con la esperada por Conventional Commits en el 100 % de
  los casos de prueba (`feat` → minor, `fix` → patch, `!` → major).
- **SC-004**: Fusionar el PR de release produce tag y GitHub Release en menos de 5 minutos, y el
  workflow de imagen versionada se dispara en el 100 % de los casos.
- **SC-005**: 0 PRs de release, tags o releases duplicados tras repetir la ejecución del workflow.
- **SC-006**: Se publica `v0.1.0` con su entrada en `CHANGELOG.md`, y un `fix` posterior propone
  `v0.1.1`.
- **SC-007**: Un colaborador nuevo logra redactar un título de PR que genera la entrada de changelog
  esperada leyendo solo el README o `CLAUDE.md`.
- **SC-008**: El README y `CLAUDE.md` presentan la misma convención (0 contradicciones en una
  revisión).

## Assumptions

- LAB-15 está implementado (CI en GitHub Actions) y LAB-77 protege `main`: el PR de release pasa por
  PR, checks requeridos y squash como cualquier otro cambio. La validación de título de LAB-77
  (`pr-title`) ya garantiza que el commit de squash sea un Conventional Commit con clave LAB.
- Se publica una **única versión de repositorio** (`vX.Y.Z`, sin prefijo de componente), según el
  criterio de aceptación; los tres paquetes se versionan de forma coherente bajo ese tag. Si más
  adelante se necesitan versiones independientes por componente, sería un cambio posterior.
- `frontend/` e `ingest/` hoy son carpetas vacías (`.gitkeep`) y el backend está en
  `0.0.1-SNAPSHOT`. Esta historia registra los tres paquetes y deja listos los archivos de versión
  mínimos que necesite el release; la creación real del código de frontend e ingest queda fuera de
  alcance. La forma exacta de manejar paquetes aún sin código se decide en el plan.
- Mientras la versión sea `0.x`, un cambio breaking puede subir la minor en lugar de la major
  (comportamiento habitual de release-please); el plan confirma la configuración elegida, y a
  partir de `1.0.0` aplica la regla estándar.
- El `v0.1.0` se obtiene configurando la versión inicial del manifiesto de forma que el primer
  release resulte en `0.1.0`.
- El PR de release debe poder disparar la CI para cumplir los checks requeridos; la elección del
  mecanismo (token, GitHub App u otro) queda para el plan.
- El workflow de imagen versionada de LAB-78 se implementa en otra historia; aquí solo se garantiza
  que el tag y el GitHub Release creados puedan dispararlo.
- Fuera de alcance: construcción, publicación y firma de imágenes (LAB-78 y siguientes), publicación
  en registros de paquetes (Maven Central, npm, PyPI), versionado independiente por componente y
  despliegues.
