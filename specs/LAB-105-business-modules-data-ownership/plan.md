# Implementation Plan: Módulos por negocio con dueño de datos y reglas verificadas

**Branch**: `refactor/LAB-105-business-modules-data-ownership` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-105-business-modules-data-ownership/spec.md`

## Summary

Reorganizar el monolito en cinco módulos de negocio (`chat`, `retrieval`, `orders`, `catalog`, `tickets`) más el
módulo abierto `shared`, eliminar el módulo vacío `tools` y hacer **ejecutables** las reglas: un esquema de Postgres por
módulo (migraciones Flyway propias en `db/migration/<modulo>`, sin FKs ni joins entre esquemas), capas
`domain`/`application`/`infrastructure` con un dominio libre de Spring AI, Kafka, JPA y Reactor (ArchUnit), verificación
y documentación con Modulith, y un ADR de límites de contexto y repositorios.

Enfoque técnico (detalle en [research.md](research.md)):

- Cada módulo declara un bean `ModuleSchema`; un migrador en `shared` ejecuta **un Flyway por módulo** (esquema,
  carpeta y tabla de historial propios). La base de datos es **obligatoria** (aclaración 2026-10-27): sin Postgres el
  arranque falla nombrando la causa; las pruebas que levantan la aplicación usan una Postgres desechable
  (Testcontainers, contenedor único por ejecución).
- Las herramientas `@Tool` viven en el subpaquete `api` de cada módulo (interfaz nombrada de Modulith) y se exponen como
  `ToolCallbackProvider`; `chat` solo recibe la lista y la compone.
- Reglas ejecutables con el mismo patrón de LAB-111: reglas ArchUnit + fixtures de violación, más una verificación de
  esquemas (lint de SQL y prueba contra Postgres real con Testcontainers), cada una con su prueba negativa.
- Documenter de Modulith escribe en `docs/modulos/` y CI falla si el resultado difiere de lo versionado.

## Technical Context

**Language/Version**: Java 21, Spring Boot 4.1.2, Spring AI 2.0.2, Spring Modulith 2.1.2

**Primary Dependencies**: se añaden `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`,
`flyway-database-postgresql` y el driver `org.postgresql:postgresql` (runtime); en test, Testcontainers PostgreSQL
(`spring-boot-testcontainers`, `testcontainers-postgresql`). ArchUnit ya llega por `spring-modulith-starter-test`.

**Storage**: PostgreSQL 16 + pgvector (ADR-0002), imagen `pgvector/pgvector:0.8.7-pg16` de `infra/docker-compose.yml`;
solo esquemas vacíos con su historial de Flyway (sin tablas de negocio).

**Testing**: JUnit 5, AssertJ, ArchUnit, Modulith `ApplicationModules`/`Documenter`, Testcontainers (todas las pruebas de contexto, con un
contenedor compartido), BlockHound ya activo (LAB-111). Cobertura JaCoCo >= 80 % (BUNDLE de líneas).

**Target Platform**: Linux (contenedor del backend), desarrollo local en Windows/Linux con Docker.

**Project Type**: web-service (monolito modular, `backend/`) + documentación (`docs/`).

**Performance Goals**: sin metas nuevas; el arranque añade N migraciones triviales (una por módulo). El contrato y la
latencia del chat de LAB-12/LAB-111 no cambian.

**Constraints**: JDBC bloqueante solo en `infrastructure` y fuera del bucle de eventos (ADR-0011); las migraciones
corren al arrancar en el hilo principal, no en el bucle de eventos; max. 121 columnas, Checkstyle/PMD limpios.

**Infrastructure**: sin cambios previstos. `shoplab-infra` ya despliega Postgres y entrega al backend las variables
`SPRING_DATASOURCE_*` (research D-4); la tarea T002a lo verifica y T045a aplica cualquier cambio que resulte necesario en
`infra/` o en `shoplab-infra`.

**Scale/Scope**: 5 módulos de negocio (3 casi vacíos, 2 nuevos vacíos), 1 módulo compartido, ~0 clases de negocio
nuevas; ~6 clases de infraestructura (migrador, `ModuleSchema`, config por módulo) y ~5 clases de pruebas de
arquitectura.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación | Estado |
|---|---|---|
| I. Monolito modular primero | Módulos sin compartir tablas, verificados por Modulith; no se extrae nada | Pasa |
| II. Hexagonal y dominio puro | Es el núcleo de la historia; se hace ejecutable con ArchUnit | Pasa |
| III. Identificadores en inglés, errores tipados | Los fallos de base de datos al arrancar se lanzan como `ModuleMigrationException` (subtipo de `TechnicalException`) con códigos `DATABASE_UNAVAILABLE` y `DATABASE_MIGRATION_FAILED` en `ResponseCodeEnum`; `ModuleSchema` usa `IllegalArgumentException` solo para errores de programación del cableado (nunca llegan a HTTP) | Pasa |
| IV. Test-first, cobertura >= 80 % | Reglas con prueba negativa; la prueba de esquemas usa Postgres real; paquetes `config`/`model`/`dto` excluidos de JaCoCo siguen sin lógica | Pasa |
| V. RAG con evaluación | No aplica (F0, sin cambios de recuperación) | N/A |
| VI. Observabilidad y datos seguros | Credenciales de BD por entorno, nunca en el repositorio; logs del migrador sin datos de negocio | Pasa |
| VII. Simplicidad y ADR | ADR obligatorio (extracción de módulos) incluido; sin roles de BD por módulo ni eventos nuevos (YAGNI, ver research D-8) | Pasa |

Sin violaciones; **Complexity Tracking** no aplica.

**Re-evaluación post-diseño**: sin cambios; el diseño de Fase 1 no añade piezas fuera de lo anterior.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-105-business-modules-data-ownership/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── module-structure.md
│   ├── schema-isolation.md
│   └── architecture-rules.md
├── checklists/requirements.md
└── tasks.md              # /speckit-tasks (no lo crea /speckit-plan)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # + jdbc, flyway, postgresql, testcontainers (test)
├── src/main/java/com/shoplab/helpdesk/
│   ├── chat/{api,domain,application,infrastructure,config}/
│   │   └── infrastructure/out/ollama/ToolCallbackComposer.java   # compone los ToolCallbackProvider de otros módulos
│   ├── retrieval/{api,domain,application,infrastructure,config}/   # solo package-info + ModuleSchema
│   ├── orders/…                              # NUEVO (vacío salvo package-info + ModuleSchema)
│   ├── catalog/…                             # NUEVO (vacío salvo package-info + ModuleSchema)
│   ├── tickets/…                             # ya existe; + ModuleSchema
│   ├── tools/                                # ELIMINADO
│   └── shared/
│       ├── exception/                        # + ModuleMigrationException, códigos DATABASE_*
│       ├── config/                           # + DatabaseConfig (migrador)
│       └── infrastructure/out/database/      # ModuleSchema, ModuleSchemaMigrator
├── src/main/resources/
│   ├── application.yml                       # + spring.datasource, flyway.enabled=false
│   └── db/migration/{chat,retrieval,orders,catalog,tickets}/V1__baseline.sql
└── src/test/java/com/shoplab/helpdesk/
    ├── ModularityTests.java                  # módulos esperados actualizados + Documenter
    ├── architecture/
    │   ├── ModuleArchitectureRules.java      # D1..D5
    │   ├── ModuleArchitectureTests.java      # producción + fixtures de violación
    │   └── violations/…                      # nuevas fixtures por regla
    └── schema/
        ├── SchemaIsolationRules.java         # lint de SQL
        ├── SchemaIsolationTests.java         # unitaria con fixtures
        └── ModuleSchemasIntegrationTests.java  # Testcontainers: esquemas, historial, FKs

docs/
├── modulos/                                  # NUEVO: generado por Documenter (versionado)
└── adr/0015-limites-de-contexto-y-repositorios.md   # + fila en docs/adr/README.md

.github/workflows/ci.yml                      # + paso de deriva de docs/modulos
```

**Structure Decision**: se mantiene la estructura por módulo ya usada en `chat` (`domain`, `application`,
`infrastructure`, `config`) y se añade `api` como interfaz nombrada de Modulith para lo que un módulo publica
(herramientas del agente y tipos públicos). `config` queda como paquete de cableado permitido porque
`ChatProperties` es consumido por la capa `application` (hoy `StreamChatUseCase`) y moverlo a `infrastructure` crearía
una dependencia prohibida. `shared` queda exento de las reglas de capas.
