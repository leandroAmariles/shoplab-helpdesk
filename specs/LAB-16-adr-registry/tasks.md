---

description: "Task list for LAB-16 Registro de decisiones de arquitectura (ADR)"
---

# Tasks: Registro de decisiones de arquitectura (ADR)

**Input**: Design documents from `/specs/LAB-16-adr-registry/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md (sin `contracts/`: no hay interfaces externas)

**Tests**: No se solicitan pruebas automatizadas (solo documentación). La verificación son los pasos de [quickstart.md](quickstart.md), incluidos en cada historia y en la fase final.

**Organization**: Tareas agrupadas por historia de usuario. Todo el contenido es Markdown en español, UTF-8, LF, sangría con espacios (`.editorconfig`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: Historia de usuario a la que pertenece (US1–US4)

## Phase 1: Setup (línea base)

**Purpose**: Confirmar el punto de partida antes de editar.

- [X] T001 Confirmar que la rama actual es `docs/LAB-16-adr-registry` y que `git status --short` no muestra cambios fuera de `specs/LAB-16-adr-registry/`
- [X] T002 Releer `docs/adr/0000-plantilla.md`, `docs/adr/0001-monolito-modular-primero.md` y `docs/adr/0002-postgres-pgvector.md` contra la tabla de auditoría de `specs/LAB-16-adr-registry/research.md` y anotar cualquier diferencia nueva en esa tabla

**Checkpoint**: la línea base coincide con la auditoría de research.md.

---

## Phase 2: Foundational

No hay prerrequisitos bloqueantes adicionales: la plantilla (US1) es la base de las demás historias y se aborda primero en la Fase 3.

---

## Phase 3: User Story 1 - Plantilla ADR reutilizable (Priority: P1) 🎯 MVP

**Goal**: Una plantilla en `docs/adr` que permita crear un ADR completo sin ayuda (FR-001 a FR-003).

**Independent Test**: Quickstart §1; una persona rellena la plantilla para una decisión ficticia y obtiene un ADR completo.

- [X] T003 [US1] En `docs/adr/0000-plantilla.md` conservar el título `# ADR-NNNN: Título`, `- Estado: propuesto | aceptado | sustituido por ADR-XXXX`, `- Fecha: AAAA-MM-DD` y las secciones `## Contexto`, `## Decisión`, `## Consecuencias`, y añadir una línea opcional `- Sustituye a: ADR-YYYY (solo si reemplaza a otro)` justo después de `Fecha`
- [X] T004 [US1] En `docs/adr/0000-plantilla.md` añadir un bloque de instrucciones (comentario HTML o nota inicial en español) que indique: copiar el archivo como `NNNN-titulo-corto.md`; número de cuatro dígitos secuencial que nunca se reutiliza (0000 queda reservado a la plantilla); los estados permitidos; que un ADR `aceptado` no se edita ni se borra, solo se sustituye; y un enlace a `README.md` de la carpeta
- [X] T005 [US1] Ejecutar Quickstart §1 contra `docs/adr/0000-plantilla.md` y corregir lo que falte

**Checkpoint**: la plantilla es autosuficiente y claramente distinta de un ADR real.

---

## Phase 4: User Story 2 - ADR-0001: monolito modular primero (Priority: P1)

**Goal**: ADR-0001 conforme a la plantilla con contexto, decisión y consecuencias completos (FR-004, FR-007).

**Independent Test**: Quickstart §2 sobre el ADR-0001; leerlo solo basta para explicar la decisión.

- [X] T006 [P] [US2] Auditar `docs/adr/0001-monolito-modular-primero.md` contra US2 de `specs/LAB-16-adr-registry/spec.md`: estado `aceptado`; Contexto con objetivo final de microservicios con Kafka y CQRS y foco en aprender RAG; Decisión con F0–F3 en monolito modular y extracción desde F4; Consecuencias con fronteras verificables y no compartir tablas entre módulos
- [X] T007 [US2] Si T006 detecta diferencias de forma o de contenido, corregirlas en `docs/adr/0001-monolito-modular-primero.md` sin cambiar la decisión ni la fecha `2026-10-23`; si no hay diferencias, no modificar el archivo y registrarlo en el resumen
- [X] T008 [US2] Ejecutar Quickstart §2 para `docs/adr/0001-monolito-modular-primero.md`

**Checkpoint**: ADR-0001 verificado.

---

## Phase 5: User Story 3 - ADR-0002: Postgres + pgvector (Priority: P1)

**Goal**: ADR-0002 conforme a la plantilla con contexto, decisión y consecuencias completos (FR-005, FR-007).

**Independent Test**: Quickstart §2 sobre el ADR-0002.

- [X] T009 [P] [US3] Auditar `docs/adr/0002-postgres-pgvector.md` contra US3 de `specs/LAB-16-adr-registry/spec.md`: estado `aceptado`; Contexto con datos relacionales, vectores y texto completo; Decisión con base única, índice vectorial (HNSW) y texto completo (`tsvector` con GIN); Consecuencias con una sola base que operar, búsqueda híbrida en SQL y una base por servicio en F5
- [X] T010 [US3] Si T009 detecta diferencias, corregirlas en `docs/adr/0002-postgres-pgvector.md` sin cambiar la decisión ni la fecha `2026-10-23`; si no hay diferencias, no modificar el archivo y registrarlo en el resumen
- [X] T011 [US3] Ejecutar Quickstart §2 para `docs/adr/0002-postgres-pgvector.md`

**Checkpoint**: ADR-0002 verificado. US2 y US3 son independientes entre sí.

---

## Phase 6: User Story 4 - Registro descubrible y coherente (Priority: P2)

**Goal**: Un índice con guía de uso enlazado desde el README raíz (FR-006, FR-008, FR-010).

**Independent Test**: Quickstart §3, §4 y §5; desde el README se llega al índice en menos de 2 minutos.

- [X] T012 [US4] Crear `docs/adr/README.md` en español con: propósito del registro; tabla de índice (número, título con enlace, estado) para ADR-0001 a ADR-0004, sin incluir la plantilla; estados permitidos; regla de numeración (cuatro dígitos, secuencial, nunca reutilizado, se confirma al integrar y quien integra después renumera); pasos para añadir un ADR desde `0000-plantilla.md`; y el procedimiento de sustitución (el original solo cambia su línea de estado a `sustituido por ADR-XXXX`, el nuevo añade `Sustituye a: ADR-YYYY`, nada se borra)
- [X] T013 [P] [US4] En `README.md` (raíz), línea «Las decisiones de arquitectura se registran como ADR en `docs/adr/`.» (sección de fases), añadir el enlace al índice `docs/adr/README.md` y una mención breve de que se parte de `docs/adr/0000-plantilla.md`
- [X] T014 [P] [US4] Comprobar que `docs/adr/0003-ollama-modelos-locales.md` y `docs/adr/0004-spring-boot-4-spring-ai-2.md` tienen `- Estado:`, `- Fecha:` y las tres secciones; ajustar solo la forma si falta algo, sin renumerar ni cambiar la decisión (FR-010)
- [X] T015 [US4] Ejecutar Quickstart §3, §4 y §5 y corregir lo que falle

**Checkpoint**: el registro se descubre desde el README y es coherente.

---

## Phase 7: Polish & verificación final

- [ ] T016 Ejecutar Quickstart §6 (prueba de usuario nuevo) o dejar constancia en la PR de que se hará en revisión
- [X] T017 Ejecutar Quickstart §7: `git status --short` y `git diff --staged` deben mostrar solo cambios en `docs/adr/`, `README.md` y `specs/LAB-16-adr-registry/`; confirmar UTF-8 y LF en los archivos nuevos o editados
- [ ] T018 Hacer commit atómico con mensaje `LAB-16 docs(adr): add ADR index and strengthen template` y, si corresponde, abrir la PR con la plantilla del repositorio

---

## Dependencies & Execution Order

- Fase 1 → US1 (T003–T005) → US2 y US3 en paralelo → US4 → Polish.
- US2 y US3 dependen de US1 solo para validar conformidad con la plantilla actualizada.
- US4 (T012) depende de que 0001–0004 estén verificados para listar su estado correcto; T013 y T014 son paralelizables con T012 (archivos distintos).

### Parallel Opportunities

- T006 ∥ T009 (auditoría de 0001 y 0002)
- T013 ∥ T014 tras terminar T012 o junto a él (archivos distintos)

## Implementation Strategy

- **MVP**: US1 + US2 + US3 cubren los criterios de aceptación de Jira (plantilla, ADR-0001 y ADR-0002).
- **Incremental**: US4 (P2) añade descubrimiento y guía de mantenimiento; puede ir en la misma PR dado su tamaño.
