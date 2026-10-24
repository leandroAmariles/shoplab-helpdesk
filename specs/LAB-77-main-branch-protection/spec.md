# Feature Specification: Protección de main con checks requeridos

**Feature Branch**: `chore/LAB-77-main-branch-protection`

**Created**: 2026-10-24

**Status**: Draft

**Input**: User description: "LAB-77 (F0-12) Como desarrollador, quiero que nada llegue a `main` sin pasar por PR y por todos los checks. Criterios: ruleset en `main` sin push directo ni force push, PR obligatorio; checks requeridos: build y tests con gate de cobertura, Qodana y Trivy; título del PR validado con Conventional Commits (por ejemplo con `amannn/action-semantic-pull-request`), porque release-please lee el commit de squash; rama actualizada antes de fusionar y squash merge con la clave LAB en el título, por ejemplo `feat(chat): [LAB-12] respuesta en streaming`; documentado en el README. Depende de: LAB-15, LAB-75, LAB-76."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - `main` solo recibe cambios vía pull request (Priority: P1)

Como desarrollador, quiero que `main` rechace cualquier push directo, force push o borrado de la
rama, para que todo cambio pase por un pull request revisable y trazable.

**Why this priority**: Es el núcleo de la historia: sin esta barrera, los checks de las demás
historias son opcionales y se pueden saltar con un push directo.

**Independent Test**: Intentar `git push origin main` con un commit local, y un force push, y
comprobar que ambos son rechazados; abrir un pull request y comprobar que es la única vía de
integración.

**Acceptance Scenarios**:

1. **Given** un commit local sobre `main`, **When** se intenta un push directo a `main`, **Then**
   el remoto lo rechaza indicando que se requiere un pull request.
2. **Given** `main`, **When** se intenta un force push o el borrado de la rama, **Then** el remoto
   lo rechaza.
3. **Given** una rama de trabajo con cambios, **When** se abre un pull request hacia `main`,
   **Then** es posible fusionarlo únicamente cuando se cumplen el resto de reglas.
4. **Given** el propietario del repositorio, **When** intenta saltarse la regla, **Then** la regla
   también se le aplica (sin bypass).

---

### User Story 2 - Checks de calidad y seguridad requeridos antes de fusionar (Priority: P1)

Como desarrollador, quiero que el pull request no se pueda fusionar hasta que pasen el build y los
tests con gate de cobertura, el análisis de Qodana y el escaneo de Trivy, para que ningún cambio con
fallos de calidad o de seguridad llegue a `main`.

**Why this priority**: Convierte los gates de LAB-15, LAB-75 y LAB-76 de informativos en
obligatorios; sin esto la protección de rama solo exige un pull request vacío de garantías.

**Independent Test**: Abrir un pull request con un check fallido (p. ej. cobertura por debajo del
umbral) y comprobar que el botón de fusión queda bloqueado; corregirlo y comprobar que se habilita.

**Acceptance Scenarios**:

1. **Given** un pull request con el build, los tests o el gate de cobertura fallidos, **When** se
   intenta fusionar, **Then** la fusión está bloqueada.
2. **Given** un pull request con el check de Qodana o el de Trivy fallido, **When** se intenta
   fusionar, **Then** la fusión está bloqueada.
3. **Given** un pull request cuyos checks aún están en ejecución o no se han reportado, **When** se
   intenta fusionar, **Then** la fusión está bloqueada hasta que terminen en verde.
4. **Given** un pull request que solo toca archivos de un componente, **When** el pipeline omite
   los jobs de los demás componentes, **Then** los checks requeridos se consideran satisfechos y
   la fusión no queda bloqueada indefinidamente por checks que nunca se ejecutaron.
5. **Given** un pull request con todos los checks requeridos en verde, **When** se cumplen el resto
   de reglas, **Then** la fusión está habilitada.

---

### User Story 3 - Título del PR con Conventional Commits y clave LAB (Priority: P1)

Como desarrollador, quiero que el título del pull request sea validado automáticamente contra
Conventional Commits y exija la clave de la historia, para que el commit de squash resultante sea
legible por release-please y trazable a Jira.

**Why this priority**: Con squash merge el título del PR se convierte en el único mensaje de commit
en `main`; si es inválido, release-please no puede calcular versiones ni changelog.

**Independent Test**: Abrir un pull request con título `arreglos varios` y comprobar que el check
de título falla; editarlo a `feat(chat): [LAB-12] respuesta en streaming` y comprobar que pasa sin
necesidad de un nuevo commit.

**Acceptance Scenarios**:

1. **Given** un pull request con título `feat(chat): [LAB-12] respuesta en streaming`, **When** se
   valida, **Then** el check de título pasa.
2. **Given** un título que no sigue Conventional Commits (p. ej. `arreglos varios`), **When** se
   valida, **Then** el check falla indicando el formato esperado.
3. **Given** un título válido en Conventional Commits pero sin la clave de historia `[LAB-<n>]`,
   **When** se valida, **Then** el check falla indicando que falta la clave.
4. **Given** un título inválido que se edita para corregirlo, **When** se guarda el cambio,
   **Then** el check se vuelve a evaluar y pasa sin requerir un nuevo commit.
5. **Given** un título cuyo tipo no pertenece al conjunto permitido (`feat`, `fix`, `refactor`,
   `chore`, `docs`, `test`, etc.), **When** se valida, **Then** el check falla.
6. **Given** el check de título, **When** se configura la protección, **Then** es un check
   requerido: un título inválido bloquea la fusión.

---

### User Story 4 - Rama al día y squash merge como único método de fusión (Priority: P2)

Como desarrollador, quiero que el pull request esté actualizado con `main` antes de fusionarse y que
la única forma de fusionar sea squash merge, para que `main` mantenga un historial lineal con un
commit por historia y los checks se hayan ejecutado sobre el estado real que se va a integrar.

**Why this priority**: Refuerza la garantía de las historias 2 y 3, pero la protección básica ya
funciona sin ella.

**Independent Test**: Con dos pull requests abiertos, fusionar el primero y comprobar que el segundo
exige actualizarse con `main` y volver a pasar los checks antes de poder fusionarse; comprobar que
solo la opción "Squash and merge" está disponible.

**Acceptance Scenarios**:

1. **Given** un pull request cuya rama está por detrás de `main`, **When** se intenta fusionar,
   **Then** la fusión está bloqueada hasta actualizar la rama y volver a pasar los checks.
2. **Given** un pull request listo para fusionar, **When** se abre el menú de fusión, **Then** solo
   está disponible squash merge (no merge commit ni rebase merge).
3. **Given** una fusión por squash, **When** se completa, **Then** el commit en `main` usa el título
   del pull request como mensaje, con tipo, alcance opcional y clave LAB.
4. **Given** una fusión completada, **When** se revisa el historial de `main`, **Then** es lineal,
   con un único commit por pull request.

---

### User Story 5 - Reglas documentadas y reproducibles (Priority: P2)

Como desarrollador, quiero que la política de protección esté documentada en el README y que su
definición sea reproducible, para que cualquier colaborador entienda cómo contribuir y se pueda
restaurar o auditar la configuración.

**Why this priority**: Sin documentación los rechazos del remoto resultan opacos, pero no afecta a
la protección en sí.

**Independent Test**: Leer la sección del README y seguirla para abrir un pull request válido hasta
fusionarlo; comparar la configuración activa con la definición versionada.

**Acceptance Scenarios**:

1. **Given** el README, **When** se consulta, **Then** describe el flujo (rama → PR → checks →
   squash), la lista de checks requeridos, el formato del título con un ejemplo y cómo corregir un
   título inválido.
2. **Given** el repositorio, **When** se revisa, **Then** existe una definición versionada del
   ruleset que refleja la configuración activa, con instrucciones para aplicarla o restaurarla.
3. **Given** la lista de checks requeridos documentada, **When** se compara con la configuración
   activa, **Then** coinciden.

---

### Edge Cases

- ¿Qué pasa con los pull requests de Dependabot (LAB-76)? Deben cumplir las mismas reglas; sus
  títulos deben seguir Conventional Commits y la clave LAB, o excluirse de forma explícita y
  documentada de esa validación concreta.
- ¿Qué pasa si un job requerido se omite por filtros de rutas? No debe bloquear la fusión (ver
  historia 2, escenario 4); el check requerido debe existir siempre y reportar un resultado.
- ¿Qué pasa si un check requerido cambia de nombre? La fusión queda bloqueada esperando un check que
  nunca llegará; los nombres requeridos deben ser estables y cualquier cambio exige actualizar el
  ruleset en el mismo cambio.
- ¿Qué pasa si hay un incidente y hay que corregir `main` urgentemente? También pasa por pull
  request; la rapidez se obtiene con un pull request mínimo, no saltándose la regla.
- ¿Qué pasa si el título contiene la clave LAB pero en otra posición o formato (`LAB-12` sin
  corchetes)? Falla; el formato es único para que sea parseable.
- ¿Qué pasa con un pull request que referencia varias historias? El título lleva la clave principal;
  las demás pueden ir en el cuerpo.
- ¿Qué pasa si se edita el título tras la aprobación de los checks? El check de título se reevalúa y
  puede bloquear la fusión de nuevo.
- ¿Qué pasa con las ramas ya fusionadas? Siguen siendo borrables; el borrado de ramas de trabajo no
  está restringido.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `main` MUST rechazar pushes directos; todo cambio MUST integrarse mediante pull
  request.
- **FR-002**: `main` MUST rechazar force pushes y el borrado de la rama.
- **FR-003**: Las reglas MUST aplicarse también a los administradores del repositorio (sin bypass).
- **FR-004**: La fusión de un pull request MUST estar bloqueada hasta que pasen los checks de build
  y tests con gate de cobertura, Qodana y Trivy.
- **FR-005**: Los checks requeridos MUST tener nombres estables y reportar siempre un resultado,
  incluso cuando el filtrado de rutas omita jobs de componentes que no cambiaron, de modo que
  ningún pull request quede bloqueado esperando un check que no se ejecutará.
- **FR-006**: Un check requerido omitido por filtrado de rutas MUST considerarse satisfecho; uno
  fallido, cancelado o pendiente MUST bloquear la fusión.
- **FR-007**: El pipeline MUST incluir un check que valide el título del pull request contra
  Conventional Commits (por ejemplo con `amannn/action-semantic-pull-request`).
- **FR-008**: El título MUST tener el formato `type(scope): [LAB-<n>] descripción`, con `scope`
  opcional y `type` dentro del conjunto permitido por el proyecto.
- **FR-009**: El check de título MUST ejecutarse al abrir, reabrir, sincronizar y editar el título
  del pull request, y MUST reevaluarse al editarlo sin requerir un nuevo commit.
- **FR-010**: Cuando el título sea inválido, el check MUST indicar claramente el formato esperado y
  qué parte incumple (tipo o clave LAB).
- **FR-011**: El check de título MUST ser un check requerido para la fusión.
- **FR-012**: La rama del pull request MUST estar actualizada con `main` antes de fusionarse; si
  queda desactualizada, MUST actualizarse y volver a pasar los checks.
- **FR-013**: El único método de fusión permitido MUST ser squash merge; merge commit y rebase merge
  MUST estar deshabilitados.
- **FR-014**: El mensaje del commit de squash MUST derivarse del título del pull request.
- **FR-015**: El historial de `main` MUST ser lineal.
- **FR-016**: El README MUST documentar el flujo de contribución, la lista de checks requeridos, el
  formato del título con ejemplo y cómo resolver los rechazos más comunes.
- **FR-017**: La definición del ruleset MUST estar versionada en el repositorio, con instrucciones
  para aplicarla o restaurarla, y MUST coincidir con la configuración activa.
- **FR-018**: La historia MUST NOT modificar el comportamiento de los gates existentes (LAB-15,
  LAB-75, LAB-76); solo los hace obligatorios y añade el check de título.
- **FR-019**: El job de validación de título MUST usar permisos mínimos.
- **FR-020**: Los pull requests de bots (Dependabot) MUST someterse a las mismas reglas, o su
  tratamiento diferenciado MUST quedar documentado explícitamente.

### Key Entities

- **Ruleset de `main`**: conjunto de reglas de protección de la rama principal (sin push directo, sin
  force push, PR obligatorio, checks requeridos, rama al día, squash only).
- **Check requerido**: verificación del pipeline cuyo resultado en verde es condición para fusionar;
  se identifica por un nombre estable.
- **Título de PR**: texto con formato `type(scope): [LAB-<n>] descripción` que pasa a ser el mensaje
  del commit de squash y la fuente de versionado de release-please.
- **Commit de squash**: único commit que un pull request deja en `main`.
- **Definición versionada del ruleset**: representación en el repositorio de la configuración de
  protección, usada para auditarla y restaurarla.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 0 commits llegan a `main` sin pasar por un pull request (verificable en el historial
  desde la activación).
- **SC-002**: El 100 % de los intentos de push directo y force push a `main` son rechazados.
- **SC-003**: Ningún pull request con un check requerido fallido o pendiente se puede fusionar (0
  fusiones en el caso de prueba).
- **SC-004**: El 100 % de los commits que entran a `main` tras la activación tienen un mensaje que
  cumple Conventional Commits y contiene una clave `LAB-<n>`.
- **SC-005**: Un título inválido se corrige y el check se vuelve verde en menos de 2 minutos, sin
  nuevos commits.
- **SC-006**: Un pull request que solo toca un componente se puede fusionar sin esperar a checks de
  componentes no afectados.
- **SC-007**: Un colaborador nuevo logra fusionar un primer pull request válido siguiendo solo el
  README, sin ayuda externa.
- **SC-008**: La configuración activa y la definición versionada no presentan diferencias en una
  auditoría.

## Assumptions

- LAB-15, LAB-75 y LAB-76 están implementados: existen en `.github/workflows/ci.yml` los jobs de
  build y tests con cobertura, `qodana` y `trivy`, además del job agregador `ci-gate`, que reporta
  siempre y trata los jobs omitidos como satisfechos. El check requerido de build/tests/cobertura
  puede apoyarse en ese agregador; la elección exacta de qué checks se listan queda para el plan.
- El ruleset se aplica en GitHub fuera del código de la aplicación; esta historia entrega su
  definición versionada y la documentación, y su activación se verifica en el repositorio remoto.
- Se trabaja sin bypass para nadie, incluido el propietario; si se necesitara una excepción
  temporal, se haría editando el ruleset de forma explícita y auditable.
- Por tratarse de un repositorio con un único desarrollador, no se exige aprobación de revisores; el
  PR obligatorio y los checks son la barrera. Si el equipo crece, se añade como cambio posterior.
- El README del repositorio hoy describe el formato de commit `LAB-<n> type(scope): description`
  para commits de rama; esta historia fija el formato del título del PR (que determina el commit en
  `main`) como `type(scope): [LAB-<n>] descripción` y reconcilia el README para evitar ambigüedad.
  Los commits intermedios de la rama no se validan, porque se aplastan en la fusión.
- Los tipos permitidos son los de Conventional Commits ya usados en el proyecto (`feat`, `fix`,
  `refactor`, `chore`, `docs`, `test`, además de `hotfix` si el equipo lo adopta), ajustables en la
  configuración.
- release-please se configura en una historia posterior del mismo sprint; esta historia solo
  garantiza que el commit de squash sea legible por él.
- La repo es pública o su plan permite rulesets y Code Scanning; si no, la limitación se documenta.
- Fuera de alcance: configuración de release-please, publicación y firma de imágenes (LAB-78 y
  siguientes), revisiones obligatorias de código, CODEOWNERS y merge queue.
