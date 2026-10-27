# Implementation Plan: Esquema retrieval propiedad del módulo retrieval

**Branch**: `feature/LAB-19-retrieval-schema-ownership` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/LAB-19-retrieval-schema-ownership/spec.md`

## Summary

Añadir al módulo `retrieval` la migración `V2__knowledge_base_tables.sql` que crea `retrieval.document` y
`retrieval.chunk` (vector de 1024 dimensiones, `embedding_model` obligatorio, metadatos JSONB), un índice HNSW con
distancia coseno y un índice GIN sobre los metadatos del chunk. No hay código de producción nuevo: el migrador por
módulo, el `ModuleSchema` de `retrieval` y la línea base `V1` ya existen (LAB-105). El trabajo es el SQL más pruebas
que lo verifican contra una PostgreSQL con pgvector desechable (estructura, restricciones, índice, rendimiento,
propiedad única del esquema) y una prueba que garantiza que `ingest` no recibe credenciales de base de datos.

El único punto delicado es que Flyway deja `search_path = retrieval`, de modo que el tipo `vector` (extensión instalada
en `public`) y su clase de operadores se califican con `public.` en la migración (ver [research.md](research.md), D-1).

## Technical Context

**Language/Version**: SQL (PostgreSQL 16) para la migración; Java 21 para las pruebas

**Primary Dependencies**: Flyway (un Flyway por módulo, ya en `ModuleSchemaMigrator`), pgvector 0.8.x (imagen
`pgvector/pgvector:0.8.7-pg16`), Spring Boot 4.x, Testcontainers, JUnit 5, AssertJ

**Storage**: PostgreSQL 16 + pgvector, esquema `retrieval` (ADR-0002, ADR-0015)

**Testing**: JUnit 5 + Testcontainers sobre `SharedPostgres` (`@PostgresTest`); `SchemaIsolationTests` para el lint de
SQL; sin mocks de base de datos (Constitución IV)

**Target Platform**: backend Spring Boot en contenedor; local con `docker compose`, dev por GitOps

**Project Type**: web-service (monolito modular, módulo `retrieval`)

**Performance Goals**: 5 vecinos más cercanos sobre 10 000 chunks en < 200 ms con el índice HNSW (SC-004)

**Constraints**: sin claves foráneas ni referencias a otros esquemas; sin `SET search_path`, `CREATE SCHEMA` ni
`SET SCHEMA` en la migración (lint S1-S3); líneas ≤ 121 caracteres en el código de pruebas

**Scale/Scope**: corpus de ~25 documentos (LAB-18) y unos cientos de chunks hoy; el diseño aguanta decenas de miles

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principio | Evaluación |
|---|---|
| I. Monolito modular | Cumple: las tablas viven en el esquema de su único módulo dueño, sin claves foráneas entre esquemas. |
| II. Hexagonal | No aplica a esta historia (sin código de producción). El acceso a vectores seguirá detrás de puertos en la historia de recuperación. |
| III. Inglés y errores tipados | Cumple: tablas y columnas en inglés; un fallo de migración ya se traduce en `ModuleMigrationException` con el módulo. |
| IV. Pruebas con cobertura | Cumple: pruebas de integración contra pgvector real, escritas con la migración. Sin código nuevo que mida JaCoCo. |
| V. RAG con evaluación | No aplica todavía (desde F2). `embedding_model` deja trazabilidad para comparar modelos. |
| VI. Observabilidad y datos seguros | Cumple: sin secretos nuevos; `ingest` sin credenciales de base de datos; datos de prueba sintéticos. |
| VII. Simplicidad y ADR | Cumple: una migración, sin tablas ni columnas especulativas; sin ADR nuevo (aplica ADR-0002, 0003, 0015). |

**Gate**: PASA. Sin violaciones; Complexity Tracking vacío.

**Re-evaluación tras el diseño**: PASA. El diseño añade un archivo SQL y pruebas; no introduce piezas de arranque ni
dependencias.

## Project Structure

### Documentation (this feature)

```text
specs/LAB-19-retrieval-schema-ownership/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── retrieval-schema.md
│   └── ingest-no-database-credentials.md
├── checklists/requirements.md
└── tasks.md             # lo genera /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/retrieval/
│   ├── V1__baseline.sql                      # sin cambios
│   └── V2__knowledge_base_tables.sql         # NUEVO
└── src/test/
    ├── java/com/shoplab/helpdesk/
    │   ├── schema/RetrievalSchemaIntegrationTests.java   # NUEVO: estructura, restricciones, índice, rendimiento
    │   ├── schema/ModuleSchemasIntegrationTests.java     # se amplía: historial de retrieval con V2
    │   ├── schema/SchemaIsolationTests.java              # se amplía: tipo vector calificado y acceso ajeno
    │   └── schema/IngestHasNoDatabaseCredentialsTests.java  # NUEVO
    └── resources/schema/violations/                      # sin cambios

docs/base-conocimiento.md                     # nota breve: dónde se almacenará el corpus (esquema retrieval)
```

**Structure Decision**: Se mantiene el backend único; la historia solo toca la carpeta de migraciones del módulo
`retrieval` y sus pruebas. No se crean paquetes Java ni módulos (ya existen `retrieval/config/RetrievalSchemaConfig`).
La prueba sobre `ingest` vive en el backend junto al resto de pruebas de aislamiento porque ya se ejecutan en CI y
leen rutas relativas del repositorio (`SchemaIsolationTests`); la configuración de despliegue de `ingest` en
`shoplab-infra` queda fuera del repositorio y se verifica en la revisión de esa historia (ver contracts).

## Complexity Tracking

Sin violaciones de la Constitución.
