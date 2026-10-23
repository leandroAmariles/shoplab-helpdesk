# Implementation Plan: Backend modular con Spring Modulith y Spring AI

**Branch**: `feature/LAB-11-backend-spring-modulith` | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-11-backend-spring-modulith/spec.md`

## Summary

Crear en `backend/` el esqueleto del monolito modular: un proyecto Maven con Spring Boot 4.1.x,
Java 21 y el BOM de Spring AI 2.0 (solo gestión de versiones, sin starters de modelo todavía),
Spring Modulith para estructurar los módulos vacíos `chat`, `retrieval`, `tools` y `tickets`, un
test de arquitectura que verifica las fronteras y un ADR que registra la decisión. El backend
arranca sin servicios externos. Se incorporan los controles de calidad exigidos por la constitución
(JaCoCo, Checkstyle, PMD) para que las historias siguientes los hereden. Decisiones y versiones
verificadas en [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (release 21). El JDK local detectado es 17: hay que instalar JDK 21
(prerrequisito documentado; la construcción lo exige con Maven Enforcer).

**Primary Dependencies**: Spring Boot 4.1.2 (parent), Spring AI BOM 2.0.2, Spring Modulith BOM
2.1.2 (`spring-modulith-starter-core`), `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`.

**Storage**: N/A (sin dependencias de base de datos en esta historia; llega con la integración de
LAB-10 en historias posteriores).

**Testing**: JUnit 6 vía `spring-boot-starter-test` + `spring-modulith-starter-test`
(`ApplicationModules.verify()`), prueba de humo de contexto. JaCoCo >= 80 %.

**Target Platform**: JVM 21 en Windows 11, Linux y macOS.

**Project Type**: servicio backend (monolito modular), carpeta `backend/` del monorepo.

**Performance Goals**: construcción completa con pruebas en < 3 min (SC-002).

**Constraints**: sin servicios externos para construir ni arrancar; líneas <= 121; llaves
obligatorias; identificadores en inglés; sin secretos.

**Scale/Scope**: 1 aplicación, 4 módulos vacíos, 2 clases de prueba, 1 ADR, 1 `pom.xml`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio / restricción | Evaluación |
|---|---|
| I. Monolito modular primero | Se materializa: Spring Modulith + `verify()` en la construcción. PASS |
| II. Hexagonal | Módulos vacíos; las capas `domain`/`application`/`infrastructure` se crean dentro de cada módulo cuando llegue su primer código (YAGNI), regla recogida en [contracts/module-boundaries.md](contracts/module-boundaries.md). PASS |
| III. Identificadores en inglés | Paquetes `chat`, `retrieval`, `tools`, `tickets`; clases en inglés. Documentación en español. PASS |
| IV. Test-first / cobertura | Test de arquitectura y de contexto antes/junto con la base; JaCoCo con umbral 80 %. PASS |
| V. RAG evaluado | Fuera de alcance (desde F2). PASS |
| VI. Observabilidad y secretos | Actuator incluido; sin secretos ni credenciales. PASS |
| VII. Simplicidad / ADR | Solo lo necesario (BOM sin starters de modelo). ADR-0004 registra la plataforma. PASS |
| Restricción tecnológica "Spring Boot 3" | **CONFLICTO**: la constitución v1.0.0 y el README dicen Spring Boot 3; LAB-11 exige Spring Boot 4. La constitución prevalece, por lo que se enmienda (ver abajo). |
| Style gates | Checkstyle + PMD con reglas mínimas (121 columnas, llaves, imports sin uso). PASS |
| Flujo / ramas | Rama `feature/LAB-11-backend-spring-modulith` = directorio de la spec. PASS |

**Enmienda requerida (parte de esta historia)**: cambiar en la constitución la línea *Backend*
a "Java 21, Spring Boot 4.x, Spring AI 2.0, Spring Modulith" (versión 1.0.0 → 1.0.1, PATCH:
corrige una restricción de versión sin redefinir principios) y la línea equivalente del
`README.md`. No hay otras violaciones.

Re-evaluación tras el diseño (Fase 1): sin cambios; la complejidad añadida (JaCoCo/Checkstyle/PMD)
la exige la constitución y no requiere justificación adicional.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-11-backend-spring-modulith/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── module-boundaries.md
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks (no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/wrapper/
├── config/
│   ├── checkstyle.xml
│   └── pmd-ruleset.xml
└── src/
    ├── main/
    │   ├── java/com/shoplab/helpdesk/
    │   │   ├── ShopLabHelpdeskApplication.java
    │   │   ├── chat/package-info.java
    │   │   ├── retrieval/package-info.java
    │   │   ├── tools/package-info.java
    │   │   └── tickets/package-info.java
    │   └── resources/application.yml
    └── test/java/com/shoplab/helpdesk/
        ├── ModularityTests.java
        └── ShopLabHelpdeskApplicationTests.java

docs/adr/0004-spring-boot-4-spring-ai-2.md
```

**Structure Decision**: un único módulo Maven en `backend/` (no multi-módulo Maven): los módulos son
paquetes verificados por Modulith, coherente con ADR-0001. `backend/.gitkeep` se elimina al haber
contenido real.

## Complexity Tracking

Sin violaciones que justificar (la enmienda de la constitución se trata arriba).
