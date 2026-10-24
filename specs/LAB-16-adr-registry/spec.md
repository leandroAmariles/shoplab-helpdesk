# Feature Specification: Registro de decisiones de arquitectura (ADR)

**Feature Branch**: `docs/LAB-16-adr-registry`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "LAB-16 (F0-08) Como desarrollador, quiero registrar las decisiones de arquitectura. Criterios: plantilla ADR en `docs/adr`; ADR-0001 (monolito modular primero) y ADR-0002 (Postgres + pgvector)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Plantilla ADR reutilizable (Priority: P1)

Como desarrollador, quiero una plantilla de ADR en `docs/adr` para registrar cada nueva decisión de
arquitectura con la misma estructura, sin tener que decidir el formato cada vez.

**Why this priority**: La plantilla es el cimiento del registro: sin ella los ADR siguientes
(incluidos 0001 y 0002) no tienen un formato común y el registro se vuelve inconsistente.

**Independent Test**: Una persona que no conoce el proyecto copia la plantilla, la rellena para una
decisión ficticia y obtiene un ADR completo sin consultar a nadie.

**Acceptance Scenarios**:

1. **Given** el repositorio, **When** se abre la carpeta `docs/adr`, **Then** existe una plantilla
   identificable como tal (no confundible con un ADR real).
2. **Given** la plantilla, **When** se lee, **Then** incluye título numerado, estado, fecha y las
   secciones Contexto, Decisión y Consecuencias.
3. **Given** la plantilla, **When** se revisa el campo de estado, **Then** indica los valores
   permitidos: propuesto, aceptado y sustituido por otro ADR.
4. **Given** la plantilla, **When** se revisa su numeración, **Then** deja claro que los ADR se
   numeran de forma secuencial y que un número nunca se reutiliza.

---

### User Story 2 - ADR-0001: monolito modular primero (Priority: P1)

Como desarrollador, quiero el ADR-0001 que documenta por qué las primeras fases son un monolito
modular, para entender el contexto de esa decisión y sus consecuencias antes de tocar la estructura
del backend.

**Why this priority**: Es un criterio de aceptación explícito y condiciona la forma de todo el
backend (módulos, eventos en proceso, extracción posterior).

**Independent Test**: Leer solo el ADR-0001 y poder explicar, sin otra fuente, qué se decidió, por
qué y qué obligaciones implica.

**Acceptance Scenarios**:

1. **Given** `docs/adr`, **When** se busca el ADR-0001, **Then** existe, sigue la estructura de la
   plantilla y su estado es aceptado.
2. **Given** el ADR-0001, **When** se lee el Contexto, **Then** explica que el objetivo final es una
   arquitectura de microservicios con mensajería y CQRS, pero que las primeras fases se centran en
   aprender RAG.
3. **Given** el ADR-0001, **When** se lee la Decisión, **Then** establece que las fases F0 a F3 usan
   un monolito modular y que a partir de F4 los módulos se extraen a servicios.
4. **Given** el ADR-0001, **When** se leen las Consecuencias, **Then** recogen los beneficios (menor
   complejidad operativa, fronteras verificables) y la disciplina exigida (no compartir tablas entre
   módulos).

---

### User Story 3 - ADR-0002: Postgres + pgvector (Priority: P1)

Como desarrollador, quiero el ADR-0002 que documenta la elección de una única base de datos con
soporte vectorial y de texto completo, para saber por qué no hay almacenes separados en las fases
iniciales.

**Why this priority**: También es un criterio de aceptación explícito, y la decisión afecta a las
historias de ingesta, búsqueda híbrida y tickets.

**Independent Test**: Leer solo el ADR-0002 y poder explicar qué datos viven en la base, por qué una
sola base es suficiente y cuándo cambia esa decisión.

**Acceptance Scenarios**:

1. **Given** `docs/adr`, **When** se busca el ADR-0002, **Then** existe, sigue la estructura de la
   plantilla y su estado es aceptado.
2. **Given** el ADR-0002, **When** se lee el Contexto, **Then** explica que el sistema necesita
   datos relacionales, vectores para RAG y búsqueda de texto completo.
3. **Given** el ADR-0002, **When** se lee la Decisión, **Then** establece una única base de datos
   para todo en las fases iniciales, con índice vectorial y de texto completo.
4. **Given** el ADR-0002, **When** se leen las Consecuencias, **Then** recogen la simplicidad
   operativa, la resolución de la búsqueda híbrida en la propia base y el cambio previsto en F5
   (una base por servicio).

---

### User Story 4 - Registro descubrible y coherente (Priority: P2)

Como desarrollador, quiero que el registro de ADR sea fácil de encontrar y se mantenga consistente,
para que las decisiones se consulten y se amplíen en lugar de quedar olvidadas.

**Why this priority**: Aporta valor de mantenimiento a largo plazo, pero las historias anteriores ya
entregan el registro mínimo exigido.

**Independent Test**: Una persona nueva, partiendo solo del README, llega al listado de ADR y
encuentra la forma de añadir uno nuevo en menos de 2 minutos.

**Acceptance Scenarios**:

1. **Given** el README del proyecto, **When** se busca dónde se documentan las decisiones, **Then**
   remite a `docs/adr`.
2. **Given** la carpeta `docs/adr`, **When** se listan sus archivos, **Then** todos siguen una
   convención de nombre uniforme (número de cuatro dígitos y título corto) y se ordenan por número.
3. **Given** un ADR sustituido por otro, **When** se lee, **Then** su estado apunta al ADR que lo
   reemplaza y el nuevo ADR referencia al anterior, sin borrar el original.

---

### Edge Cases

- ¿Qué pasa si dos personas crean un ADR con el mismo número en ramas paralelas? Se resuelve
  renumerando el que se integra después; la plantilla debe indicar que el número se confirma al
  integrar.
- ¿Qué pasa cuando una decisión cambia? El ADR original no se edita ni se borra: se marca como
  sustituido y se crea uno nuevo que lo referencia.
- ¿Qué pasa con ADR que ya existen en la carpeta además de 0001 y 0002? Deben ajustarse a la misma
  plantilla; no se renumeran ni se eliminan.
- ¿Qué pasa si falta alguna sección obligatoria (Contexto, Decisión, Consecuencias)? El ADR se
  considera incompleto y no puede quedar con estado aceptado.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: El repositorio MUST contener una plantilla de ADR en `docs/adr`, claramente
  diferenciada de los ADR reales.
- **FR-002**: La plantilla MUST incluir título con número, estado, fecha y las secciones Contexto,
  Decisión y Consecuencias.
- **FR-003**: La plantilla MUST declarar los estados permitidos (propuesto, aceptado, sustituido por
  ADR-XXXX).
- **FR-004**: El repositorio MUST contener el ADR-0001 sobre «monolito modular primero» con
  estructura de la plantilla, estado aceptado, y contenido que cubra contexto, decisión (fases F0–F3
  monolito, extracción desde F4) y consecuencias.
- **FR-005**: El repositorio MUST contener el ADR-0002 sobre «Postgres + pgvector» con estructura de
  la plantilla, estado aceptado, y contenido que cubra contexto, decisión (base única con índice
  vectorial y texto completo) y consecuencias (incluido el cambio en F5).
- **FR-006**: Todos los archivos de `docs/adr` MUST seguir una convención de nombre uniforme con
  número secuencial de cuatro dígitos y título corto, de modo que su orden alfabético coincida con
  el orden de numeración.
- **FR-007**: Todo ADR con estado aceptado MUST tener las tres secciones obligatorias con contenido.
- **FR-008**: La documentación del proyecto MUST indicar dónde viven los ADR y cómo añadir uno nuevo
  a partir de la plantilla, incluyendo la regla de no editar ni borrar ADR aceptados (solo
  sustituirlos).
- **FR-009**: Los ADR MUST estar escritos en español, igual que el resto de la documentación del
  proyecto.
- **FR-010**: Los ADR existentes en `docs/adr` distintos de 0001 y 0002 MUST ser coherentes con la
  plantilla (mismas secciones y campo de estado) sin cambiar su numeración ni su decisión.

### Key Entities *(include if feature involves data)*

- **ADR**: Registro de una decisión de arquitectura. Atributos: número secuencial único, título
  corto, estado (propuesto, aceptado, sustituido), fecha, contexto, decisión, consecuencias y, si
  aplica, referencia al ADR que lo sustituye.
- **Plantilla ADR**: Documento modelo del que se copia cada nuevo ADR; define estructura y estados
  permitidos, pero no es una decisión en sí.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Una persona nueva crea un ADR completo a partir de la plantilla, sin ayuda, en menos de
  10 minutos.
- **SC-002**: El 100 % de los ADR con estado aceptado contienen las tres secciones obligatorias con
  contenido.
- **SC-003**: Una persona nueva identifica en menos de 5 minutos, leyendo solo los ADR-0001 y
  ADR-0002, por qué el sistema es un monolito modular con una única base de datos y cuándo está
  previsto cambiar ambas cosas.
- **SC-004**: Una persona llega desde el README al listado de ADR en menos de 2 minutos.
- **SC-005**: El 100 % de los archivos de `docs/adr` respetan la convención de nombre y su orden
  alfabético coincide con su numeración.

## Assumptions

- El contenido de esta historia es solo documentación; no se modifica código ni configuración de la
  aplicación.
- La carpeta `docs/adr` ya contiene una plantilla (`0000-plantilla.md`) y los ADR 0001 a 0004
  creados al inicializar el monorepo y en historias previas (LAB-9 a LAB-11). Esta historia valida
  que cumplen lo exigido por LAB-16, y completa o corrige lo que falte (p. ej. estados permitidos,
  regla de numeración o enlace desde el README) en lugar de rehacerlos.
- Los ADR 0003 (modelos locales) y 0004 (Spring Boot 4 + Spring AI 2.0) pertenecen a otras historias
  y quedan fuera de alcance salvo el ajuste de coherencia con la plantilla (FR-010).
- El formato es Markdown simple, sin herramientas de generación o validación automática de ADR.
- La fecha de los ADR 0001 y 0002 es la de su aceptación; no se reescribe el historial.
- Los ADR se mantienen en español, siguiendo la plantilla existente.
