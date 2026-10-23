---

description: "Task list for LAB-11 - Backend modular con Spring Modulith y Spring AI"
---

# Tasks: Backend modular con Spring Modulith y Spring AI

**Input**: Design documents from `/specs/LAB-11-backend-spring-modulith/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/module-boundaries.md,
quickstart.md

**Tests**: Incluidos: la spec exige un test de arquitectura (US3) y la constitución (Principio IV)
exige pruebas junto con la implementación. Escribir cada prueba antes de lo que valida y comprobar
que falla.

**Organization**: Tareas agrupadas por historia de usuario. Todas las rutas son relativas a la raíz
del repositorio. Paquete base `com.shoplab.helpdesk` = `backend/src/main/java/com/shoplab/helpdesk`.
Identificadores en inglés; líneas <= 121 caracteres; llaves en todos los bloques.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias)
- **[Story]**: historia de usuario a la que pertenece (US1..US4)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Esqueleto del proyecto Maven en `backend/`

- [X] T001 Verificar que hay JDK 21+ instalado (`java -version`; el JDK local detectado es 17). Si no, instalar JDK 21 y apuntar `JAVA_HOME` a él antes de continuar; no requiere cambios en el repositorio
- [X] T002 Crear `backend/pom.xml`: parent `spring-boot-starter-parent` 4.1.2; `groupId` `com.shoplab`, `artifactId` `helpdesk-backend`; `java.version` 21 (`maven.compiler.release`); `dependencyManagement` importando `spring-ai-bom` 2.0.2 y `spring-modulith-bom` 2.1.2 (sin versiones sueltas por componente); dependencias `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`, `spring-modulith-starter-core`; en test `spring-boot-starter-test` y `spring-modulith-starter-test`; `maven-enforcer-plugin` con `requireJavaVersion [21,)` y `requireMavenVersion [3.9,)` y mensajes claros; `spring-boot-maven-plugin`. Sin starters de modelo de Spring AI ni dependencias de base de datos
- [X] T003 Generar el Maven Wrapper en `backend/` (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) con Maven 3.9.x ejecutando `mvn -f backend/pom.xml wrapper:wrapper -Dmaven=3.9.11`
- [X] T004 [P] Crear `backend/src/main/resources/application.yml` con `spring.application.name: helpdesk-backend`, `server.port: 8080` y exposición de `health` en actuator (`management.endpoints.web.exposure.include: health`); sin secretos ni credenciales
- [X] T005 [P] Eliminar `backend/.gitkeep` (la carpeta ya tiene contenido real)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Controles de calidad y clase de arranque que necesitan todas las historias

**⚠️ CRITICAL**: Ninguna historia puede empezar hasta completar esta fase

- [X] T006 [P] Crear `backend/config/checkstyle.xml` con `LineLength` max 121, `NeedBraces` y `UnusedImports`
- [X] T007 [P] Crear `backend/config/pmd-ruleset.xml` con las reglas `UnnecessaryImport` y `UnusedFormalParameter`
- [X] T008 Configurar en `backend/pom.xml` `maven-checkstyle-plugin` (usa `config/checkstyle.xml`, falla en `verify`), `maven-pmd-plugin` (usa `config/pmd-ruleset.xml`, `check` en `verify`) y `jacoco-maven-plugin` (`prepare-agent`, `report` y `check` con mínimo 0.80 de cobertura de líneas; excluir `**/dto/**`, `**/config/**`, `**/model/**`, `**/util/mapper/**`, `**/exception/**` y `**/*Application.class`) (depende de T002, T006, T007)
- [X] T009 Crear `backend/src/main/java/com/shoplab/helpdesk/ShopLabHelpdeskApplication.java` con `@SpringBootApplication` y `main` que llama a `SpringApplication.run` (depende de T002)

**Checkpoint**: Base lista; las historias pueden empezar.

---

## Phase 3: User Story 1 - Contar con un backend que compila y arranca (Priority: P1) 🎯 MVP

**Goal**: El backend se construye con un solo comando, arranca sin servicios externos y declara
Boot 4.x, Java 21+ y el BOM de Spring AI 2.0.

**Independent Test**: `cd backend && ./mvnw verify` termina en `BUILD SUCCESS`; `./mvnw spring-boot:run`
y `curl http://localhost:8080/actuator/health` devuelve `{"status":"UP"}` (quickstart pasos 1, 2 y 4).

### Tests for User Story 1 ⚠️

> Escribir primero y comprobar que falla (o no compila) antes de T012

- [X] T010 [US1] Crear `backend/src/test/java/com/shoplab/helpdesk/ShopLabHelpdeskApplicationTests.java`: `@SpringBootTest` con un test `contextLoads` que verifica que el contexto arranca sin base de datos ni servidor de modelos (FR-008)

### Implementation for User Story 1

- [X] T011 [US1] Ejecutar `backend/mvnw -f backend/pom.xml verify` y corregir lo que falle hasta `BUILD SUCCESS` (incluye Enforcer, Checkstyle, PMD y umbral JaCoCo). Registrar en el log de la tarea las versiones resueltas con `backend/mvnw -f backend/pom.xml dependency:tree` y confirmar la compatibilidad Boot 4.1.2 + Spring AI 2.0.2 + Modulith 2.1.2 para el ADR
- [X] T012 [US1] Arrancar con `backend/mvnw -f backend/pom.xml spring-boot:run`, comprobar `GET /actuator/health` = `UP` y detener con Ctrl+C sin errores (SC-004)
- [X] T013 [P] [US1] Actualizar `README.md`: prerrequisitos (JDK 21+; Maven no necesario por el wrapper), comando para construir (`cd backend && ./mvnw verify`) y arrancar el backend en la sección "Arrancar", sustituyendo "Spring Boot 3" por "Spring Boot 4" y "Spring AI" por "Spring AI 2.0" en la descripción del backend; quitar la nota de que el arranque del backend está pendiente (FR-010)

**Checkpoint**: US1 funcional y verificable por sí sola.

---

## Phase 4: User Story 2 - Módulos de negocio vacíos con fronteras explícitas (Priority: P1)

**Goal**: Existen exactamente los módulos `chat`, `retrieval`, `tools` y `tickets`, sin lógica.

**Independent Test**: El conjunto de módulos detectados por Modulith es `{chat, retrieval, tools, tickets}`.

### Tests for User Story 2 ⚠️

- [X] T014 [US2] Crear `backend/src/test/java/com/shoplab/helpdesk/ModularityTests.java` con un test `detectsExactlyTheExpectedModules` que usa `ApplicationModules.of(ShopLabHelpdeskApplication.class)` y comprueba que los nombres de los módulos son exactamente `chat`, `retrieval`, `tools` y `tickets`. Ejecutarlo y confirmar que falla (aún no hay módulos)

### Implementation for User Story 2

- [X] T015 [P] [US2] Crear `backend/src/main/java/com/shoplab/helpdesk/chat/package-info.java` con `@ApplicationModule(displayName = "Chat")` y sin otras clases
- [X] T016 [P] [US2] Crear `backend/src/main/java/com/shoplab/helpdesk/retrieval/package-info.java` con `@ApplicationModule(displayName = "Retrieval")`
- [X] T017 [P] [US2] Crear `backend/src/main/java/com/shoplab/helpdesk/tools/package-info.java` con `@ApplicationModule(displayName = "Tools")`
- [X] T018 [P] [US2] Crear `backend/src/main/java/com/shoplab/helpdesk/tickets/package-info.java` con `@ApplicationModule(displayName = "Tickets")`
- [X] T019 [US2] Ejecutar `backend/mvnw -f backend/pom.xml test` y confirmar que `detectsExactlyTheExpectedModules` pasa. Si Modulith no reconoce un módulo solo con `package-info.java`, añadir una clase marcador mínima en ese paquete (plan B de research D4) y documentarlo en `specs/LAB-11-backend-spring-modulith/research.md`

**Checkpoint**: US1 y US2 funcionan; los cuatro módulos son detectados.

---

## Phase 5: User Story 3 - Test de arquitectura en verde que protege las fronteras (Priority: P1)

**Goal**: La verificación de arquitectura de Modulith forma parte de la construcción, pasa con la base
y falla ante ciclos o accesos a internos.

**Independent Test**: `./mvnw verify` ejecuta y pasa `ModularityTests`; una violación introducida a
propósito hace fallar la construcción (quickstart paso 3).

### Tests for User Story 3 ⚠️

- [X] T020 [US3] Añadir a `backend/src/test/java/com/shoplab/helpdesk/ModularityTests.java` un test `verifiesModuleStructure` que llama a `ApplicationModules.of(ShopLabHelpdeskApplication.class).verify()` (depende de T014, T015-T018)

### Implementation for User Story 3

- [X] T021 [US3] Ejecutar `backend/mvnw -f backend/pom.xml verify` y confirmar que `ModularityTests` se ejecuta dentro de la construcción estándar (aparece en el informe de Surefire) y pasa (FR-006, FR-007)
- [X] T022 [US3] Prueba negativa manual (no se versiona): crear temporalmente `backend/src/main/java/com/shoplab/helpdesk/chat/Probe.java` que dependa de una clase de `tickets` y otra de `tickets` que dependa de `chat` (ciclo), ejecutar `backend/mvnw -f backend/pom.xml test` y confirmar que `verifiesModuleStructure` falla nombrando los módulos; repetir con acceso a un subpaquete interno de otro módulo (SC-003). Borrar todos los archivos temporales y confirmar con `git status --short` que no quedan restos

**Checkpoint**: Las fronteras están protegidas por un test que forma parte del build.

---

## Phase 6: User Story 4 - Entender por qué se eligió esta plataforma (Priority: P2)

**Goal**: ADR de la elección de Spring Boot 4 + Spring AI 2.0 y constitución coherente con ella.

**Independent Test**: Una persona nueva lee el ADR y explica la elección, las alternativas y los riesgos (quickstart paso 5).

### Implementation for User Story 4

- [X] T023 [P] [US4] Crear `docs/adr/0004-spring-boot-4-spring-ai-2.md` con la plantilla de `docs/adr/0000-plantilla.md` (Estado `aceptado`, Fecha 2026-10-23, Contexto, Decisión, Consecuencias), en español: Spring Boot 4.1.2 + Spring AI 2.0.2 (GA junio 2026) + Spring Modulith 2.1.2 + Java 21; alternativas descartadas (Spring Boot 3.5 + Spring AI 1.x, LangChain4j, backend en Python); versiones verificadas en T011; coherencia con ADR-0001, ADR-0002 y ADR-0003
- [X] T024 [P] [US4] Enmendar `.specify/memory/constitution.md`: en "Technology & Architecture Constraints" cambiar la línea Backend a "Java 21, Spring Boot 4.x, Spring AI 2.0, Spring Modulith (`backend/`)", subir la versión a 1.0.1 y `Last Amended` a 2026-10-23, y reconciliar la cabecera "Sync Impact Report" (PATCH: corrige una restricción de versión)
- [X] T025 [US4] Revisar `docs/adr/0004-spring-boot-4-spring-ai-2.md` contra `docs/adr/0001-monolito-modular-primero.md`, `docs/adr/0002-postgres-pgvector.md` y `docs/adr/0003-ollama-modelos-locales.md` y ajustar cualquier contradicción (depende de T023)

**Checkpoint**: Las cuatro historias están completas.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T026 Ejecutar `backend/mvnw -f backend/pom.xml verify` completo desde un `clean` y comprobar que dura menos de 3 minutos (SC-002) y que Checkstyle, PMD y JaCoCo (>= 80 %) pasan
- [X] T027 Con JDK 17 (o `JAVA_HOME` apuntando a él) ejecutar `backend/mvnw -f backend/pom.xml verify` y confirmar que el Enforcer falla con un mensaje claro que exige Java 21 o superior; restaurar el JDK 21 (caso borde de la spec)
- [X] T028 Recorrer `specs/LAB-11-backend-spring-modulith/quickstart.md` de principio a fin y marcar cualquier desvío
- [X] T029 Limpieza previa al commit: `git status --short` sin `backend/target/`, `.orig`, `.log` ni archivos temporales (SC-006) y `git diff --staged` revisado
- [X] T030 Confirmar que no se generó `requests.http`: la historia no añade endpoints de negocio (solo `GET /actuator/health`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; bloquea las historias.
- **US1 (Phase 3)**: tras Foundational.
- **US2 (Phase 4)**: tras Foundational (y necesita la clase de arranque T009).
- **US3 (Phase 5)**: depende de US2 (necesita los cuatro módulos).
- **US4 (Phase 6)**: independiente del código; T025 requiere T023 y conviene tener T011 para las versiones.
- **Polish (Phase 7)**: tras todas las historias deseadas.

### Within Each Story

- La prueba se escribe antes y se ve fallar (T010 → T011; T014 → T015-T018; T020 → T021).
- T002 → T008 → T011; T006/T007 antes de T008.

### Parallel Opportunities

- T004 y T005 en paralelo (tras T002).
- T006 y T007 en paralelo.
- T015, T016, T017 y T018 en paralelo (archivos distintos).
- T013 en paralelo con T011/T012; T023 y T024 en paralelo con cualquier tarea de código.

## Parallel Example: User Story 2

```text
Task: "Crear chat/package-info.java"       (T015)
Task: "Crear retrieval/package-info.java"  (T016)
Task: "Crear tools/package-info.java"      (T017)
Task: "Crear tickets/package-info.java"    (T018)
```

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup y Phase 2 Foundational.
2. Phase 3 (US1) y **parar y validar**: build verde y arranque con `/actuator/health` en `UP`.

### Incremental Delivery

1. Base + US1 → backend que compila y arranca.
2. US2 → módulos vacíos detectados.
3. US3 → fronteras protegidas por test en el build.
4. US4 → ADR y constitución al día.
5. Polish → verificaciones finales y limpieza.

## Notes

- Versiones fijadas en `plan.md`/`research.md`; cualquier cambio se refleja en el ADR.
- Hacer commits atómicos (formato `LAB-11 type(scope): description`); no commitear `backend/target/`.
- Al terminar la implementación, ejecutar el skill `new-properties` solo si se añadieron propiedades de configuración nuevas (aquí `application.yml` define las básicas).
