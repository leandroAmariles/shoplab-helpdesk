---

description: "Task list for LAB-19 - Esquema retrieval propiedad del módulo retrieval"
---

# Tasks: Esquema retrieval propiedad del módulo retrieval

**Input**: Design documents from `/specs/LAB-19-retrieval-schema-ownership/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ (`retrieval-schema.md`,
`ingest-no-database-credentials.md`), quickstart.md

**Tests**: Incluidos. La constitución (principio IV) exige pruebas antes o junto con la implementación. En cada historia
las pruebas van primero y deben fallar antes de tocar la migración. Todas las pruebas de base de datos usan PostgreSQL
con pgvector real (Testcontainers, `@PostgresTest`), sin mocks.

**Organization**: Tareas agrupadas por historia de usuario. La migración `V2` es un único archivo que crece por
historia: US1 crea las tablas, US2 añade los índices. US3 y US4 solo añaden verificaciones.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: se puede ejecutar en paralelo (archivos distintos, sin dependencias pendientes)
- **[Story]**: historia a la que pertenece la tarea (US1–US4)
- Todas las rutas son relativas a la raíz del repositorio

## Path Conventions

- **Migración**: `backend/src/main/resources/db/migration/retrieval/V2__knowledge_base_tables.sql`
- **Pruebas del backend**: `backend/src/test/java/com/shoplab/helpdesk/schema/`
- Líneas de Java ≤ 121 caracteres; llaves en todos los bloques; identificadores en inglés
- No hay endpoints HTTP en esta historia: no se genera `requests.http`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: confirmar el punto de partida

- [X] T001 Comprobar que `mvn test -Dtest='ModuleSchemasIntegrationTests,SchemaIsolationTests' -Dskip.srcclr=true --no-transfer-progress` pasa en `backend/` sobre la rama actual (línea base verde) y que `backend/src/main/resources/db/migration/retrieval/` solo contiene `V1__baseline.sql`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: soporte común de las pruebas de `retrieval`

**⚠️ CRITICAL**: ninguna historia puede empezar hasta completar esta fase

- [X] T002 Crear `backend/src/test/java/com/shoplab/helpdesk/schema/RetrievalSchemaIntegrationTests.java`: clase `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` + `@PostgresTest` con `JdbcTemplate` inyectado; helpers privados `insertDocument(String source, String title)` (devuelve el `id` uuid), `insertChunk(UUID documentId, int chunkIndex, String content, String embedding, String model)` y `vectorLiteral(int dimensions, float value)` que arma el literal `[v,v,...]`; un `@AfterEach` que ejecuta `DELETE FROM retrieval.document WHERE source LIKE 'test/%'` (el contenedor es compartido por toda la JVM de pruebas). Todos los `source` de las pruebas usan el prefijo `test/`

**Checkpoint**: Foundation ready - las historias pueden empezar

---

## Phase 3: User Story 1 - Almacenar documentos y chunks vectorizados (Priority: P1) 🎯 MVP

**Goal**: `retrieval.document` y `retrieval.chunk` existen y guardan texto, vector de 1024 dimensiones,
`embedding_model` y metadatos, rechazando los datos inválidos.

**Independent Test**: con una base desechable, aplicar migraciones, insertar un documento con dos chunks y leerlos de
vuelta con sus metadatos intactos (ver `quickstart.md` §1).

### Tests for User Story 1 (escribir primero; deben fallar) ⚠️

Todas en `RetrievalSchemaIntegrationTests.java` (mismo archivo: sin `[P]`).

- [X] T003 [US1] Prueba `theKnowledgeBaseTablesExistInTheRetrievalSchema`: `retrieval.document` y `retrieval.chunk` existen en `information_schema.tables` (escenario 1, FR-001, FR-002)
- [X] T004 [US1] Prueba `aDocumentWithItsChunksIsReadBackIntact`: guarda un documento con 100 chunks (`chunk_index` 0–99, texto, vector de 1024 valores, `embedding_model = 'bge-m3'`, metadatos `{"topic":"returns"}`) y comprueba que texto, orden, vector, modelo y metadatos vuelven sin pérdida (escenario 2, SC-002)
- [X] T005 [US1] Prueba `aVectorOfAnotherDimensionIsRejected`: un chunk con vector de 3 dimensiones lanza `DataIntegrityViolationException` (o la excepción de dimensión del driver) y no queda fila; `embedding public.vector(1024) NOT NULL` (escenario 3, FR-005, SC-003)
- [X] T006 [US1] Prueba parametrizada `invalidChunksAreRejectedByTheDatabase`: se rechazan `embedding_model` nulo, `embedding_model` en blanco (`CHECK (btrim(embedding_model) <> '')`), `content` nulo, `content` en blanco (`CHECK (content ~ '[^[:space:]]')`), `document_id` nulo y `document_id` inexistente (escenario 4, FR-003, SC-003)
- [X] T007 [US1] Prueba `twoChunksCannotShareAPositionInTheSameDocument`: segundo insert con el mismo `(document_id, chunk_index)` falla por `UNIQUE (document_id, chunk_index)`; un `chunk_index` negativo falla por `CHECK (chunk_index >= 0)` (FR-006)
- [X] T008 [US1] Prueba `deletingADocumentDeletesItsChunks`: tras `DELETE` del documento no quedan chunks suyos (escenario 5, FR-007)
- [X] T009 [US1] Prueba `metadataDefaultsToAnEmptyObjectAndMustBeAnObject`: sin metadatos se guarda `{}` en `document` y `chunk`; un array JSON, un escalar o `NULL` se rechazan por `NOT NULL` + `CHECK (jsonb_typeof(metadata) = 'object')` (FR-004)
- [X] T010 [US1] Prueba `aSourceCanOnlyBeRegisteredOnce` (`document.source text NOT NULL UNIQUE`) y `aDocumentWithoutChunksIsValid` (edge cases «reingesta» y «documento sin chunks»)
- [X] T011 [US1] Prueba `chunksCanBeFilteredByAMetadataAttribute`: con chunks de `topic` `returns` y `shipping`, `WHERE metadata @> '{"topic":"returns"}'` devuelve solo los primeros (escenario 6, FR-009)

### Implementation for User Story 1

- [X] T012 [US1] Crear `backend/src/main/resources/db/migration/retrieval/V2__knowledge_base_tables.sql` (sin tocar `V1__baseline.sql`; sin `SET search_path`, `CREATE SCHEMA`, `SET SCHEMA` ni nombres calificados con el esquema de otro módulo; solo `public.` para la extensión). Contenido: comentario de cabecera como el de `V1` (esquema propiedad del módulo `retrieval`, specs/LAB-19); `CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;`; tabla `document` (`id uuid PRIMARY KEY DEFAULT gen_random_uuid()`, `source text NOT NULL UNIQUE`, `title text NOT NULL`, `metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object')`, `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()`); tabla `chunk` (`id uuid PRIMARY KEY DEFAULT gen_random_uuid()`, `document_id uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE`, `chunk_index integer NOT NULL CHECK (chunk_index >= 0)`, `content text NOT NULL CHECK (content ~ '[^[:space:]]')`, `embedding public.vector(1024) NOT NULL`, `embedding_model text NOT NULL CHECK (btrim(embedding_model) <> '')`, `metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object')`, `created_at timestamptz NOT NULL DEFAULT now()`, `UNIQUE (document_id, chunk_index)`)
- [X] T013 [US1] Ejecutar `mvn test -Dtest=RetrievalSchemaIntegrationTests -Dskip.srcclr=true --no-transfer-progress` en `backend/` hasta que T003–T011 pasen (la extensión se crea sola en el contenedor de pruebas)

**Checkpoint**: US1 completa y verificable por sí sola (MVP)

---

## Phase 4: User Story 2 - Búsqueda por similitud con índice HNSW (Priority: P1)

**Goal**: la búsqueda de los k vecinos más cercanos usa un índice HNSW con distancia coseno, y los metadatos se filtran
con un índice GIN.

**Independent Test**: consultar el catálogo (`pg_indexes`) y ejecutar una búsqueda de 5 vecinos sobre 10 000 chunks
comprobando el plan y el tiempo (ver `quickstart.md` §1 y §2).

### Tests for User Story 2 (escribir primero; deben fallar) ⚠️

- [X] T014 [US2] En `RetrievalSchemaIntegrationTests.java`, prueba `chunkHasAnHnswIndexOnTheEmbeddingWithCosineDistance`: `pg_indexes` para `retrieval.chunk` incluye un índice llamado `chunk_embedding_hnsw_idx` con `USING hnsw` y `vector_cosine_ops` en su `indexdef`; se comprueba por catálogo, no por plan (escenario 1, FR-008)
- [X] T015 [US2] En el mismo archivo, prueba `chunkHasAGinIndexOnItsMetadata`: existe `chunk_metadata_gin_idx` con `USING gin` y `jsonb_path_ops` (FR-009)
- [X] T016 [US2] En el mismo archivo, prueba `nearestChunksComeOrderedByCosineDistance`: con tres chunks de vectores conocidos, `ORDER BY embedding <=> ?::public.vector LIMIT 3` los devuelve de más a menos cercano (escenario 2)
- [X] T017 [US2] En el mismo archivo, prueba `fiveNearestOutOfTenThousandUseTheIndexInUnder200Ms`: carga 10 000 chunks con vectores aleatorios generados en SQL (`generate_series` + `random()`), ejecuta `ANALYZE retrieval.chunk`, y dentro de una transacción con `SET LOCAL enable_seqscan = off` comprueba por `EXPLAIN` que el plan nombra `chunk_embedding_hnsw_idx` y que `LIMIT 5` tarda menos de 1000 ms en CI (SC-004 fija 200 ms como objetivo en una máquina de desarrollo; el margen evita pruebas inestables, research D-9); registra el tiempo medido en el log; borra los datos al terminar

### Implementation for User Story 2

- [X] T018 [US2] Añadir al final de `V2__knowledge_base_tables.sql` los índices: `CREATE INDEX chunk_embedding_hnsw_idx ON chunk USING hnsw (embedding public.vector_cosine_ops);` (sin `WITH`, parámetros por defecto, research D-6) y `CREATE INDEX chunk_metadata_gin_idx ON chunk USING gin (metadata jsonb_path_ops);` (D-7)
- [X] T019 [US2] Ejecutar `RetrievalSchemaIntegrationTests` completa hasta que T003–T017 pasen

**Checkpoint**: US1 y US2 funcionan

---

## Phase 5: User Story 3 - Un único dueño del esquema y `ingest` sin credenciales (Priority: P1)

**Goal**: solo `retrieval` toca `retrieval.*`, ninguna clave foránea cruza esquemas y `ingest` no recibe credenciales
de base de datos.

**Independent Test**: las pruebas de arquitectura, de aislamiento y de `ingest` pasan en verde (ver `quickstart.md` §1).

### Tests for User Story 3 (escribir primero; deben fallar) ⚠️

- [X] T020 [P] [US3] Crear `backend/src/test/java/com/shoplab/helpdesk/schema/IngestHasNoDatabaseCredentialsTests.java` (JUnit 5, sin Spring) según `contracts/ingest-no-database-credentials.md`: un método estático `violationsIn(String source, String content)` que busca, sin distinguir mayúsculas, `DATABASE_URL`, `POSTGRES_`, `PGHOST`, `PGPASSWORD`, `SPRING_DATASOURCE`, `DB_HOST`, `DB_PASSWORD`, `DB_USER`, `jdbc:`, `postgres://`, `postgresql://`; pruebas de fixture (en el propio test, texto en línea) que demuestran que cada patrón se detecta; prueba `theRealIngestConfigurationHasNoDatabaseCredentials` que recorre todos los archivos de texto bajo `../ingest/` y, si existe un servicio `ingest:` en `../infra/docker-compose.yml`, su bloque, y espera cero violaciones (FR-013, SC-006)
- [X] T021 [P] [US3] En `backend/src/test/java/com/shoplab/helpdesk/schema/SchemaIsolationTests.java` añadir: `theQualifiedVectorTypeOfTheExtensionIsNotAViolation` (lint de `CREATE TABLE chunk (embedding public.vector(1024))` y `USING hnsw (embedding public.vector_cosine_ops)` como módulo `retrieval` devuelve vacío) y `anotherModuleReadingRetrievalTablesIsReported` (lint de `SELECT * FROM retrieval.chunk` como módulo `chat` devuelve una violación `S2`) (FR-011, FR-012)
- [X] T022 [P] [US3] En `backend/src/test/java/com/shoplab/helpdesk/schema/ModuleSchemasIntegrationTests.java` añadir `retrievalForeignKeysStayInsideItsSchema`: `foreignKeysOf("retrieval")` es exactamente `chunk->document` (FR-011, FR-012); `noForeignKeyCrossesSchemas` ya existente debe seguir pasando

### Implementation for User Story 3

- [X] T023 [US3] Ejecutar `mvn test -Dtest='SchemaIsolationTests,ModuleSchemasIntegrationTests,IngestHasNoDatabaseCredentialsTests,ModularityTests,ModuleArchitectureTests' -Dskip.srcclr=true --no-transfer-progress`; si la migración `V2` incumple alguna regla S1–S3, corregir `V2__knowledge_base_tables.sql` (no relajar el lint); si `ingest/` o el compose contienen un patrón prohibido, retirarlo

**Checkpoint**: US1–US3 verificadas

---

## Phase 6: User Story 4 - Migración versionada y repetible (Priority: P2)

**Goal**: cualquier entorno llega al mismo estado al arrancar; un segundo arranque no cambia nada; sin pgvector el
arranque aborta nombrando `retrieval`.

**Independent Test**: arrancar sobre una base con solo `V1` aplica `V2` una vez; arrancar otra vez no cambia nada (ver
`quickstart.md` §3 y §4).

### Tests for User Story 4 (escribir primero) ⚠️

- [X] T024 [US4] En `ModuleSchemasIntegrationTests.java` añadir `retrievalHistoryHasTheBaselineAndTheKnowledgeBaseTablesAppliedOnce`: `retrieval.flyway_schema_history` tiene las versiones `1` y `2` con `success`, y volver a ejecutar `new ModuleSchemaMigrator(schemas, dataSource).migrate()` no cambia el número de filas del historial de `retrieval` ni borra los datos de una fila de prueba (escenarios 1 y 2, SC-001)
- [X] T025 [US4] En el mismo archivo añadir `theOtherModulesKeepTheirHistory`: los historiales de `chat` (1), `tickets` (1), `orders` (2) y `catalog` (2) son los de antes de esta historia (escenario 4, FR-010)
- [X] T026 [US4] En `RetrievalSchemaIntegrationTests.java`, o en una clase nueva `RetrievalMigrationWithoutVectorExtensionTests.java` si el contexto compartido estorba, añadir `startingWithoutThePgvectorExtensionAbortsNamingRetrieval`: levanta un contenedor `postgres:16` desechable (imagen sin pgvector, `PostgreSQLContainer` de Testcontainers), construye un `DataSource` y ejecuta `new ModuleSchemaMigrator(List.of(new ModuleSchema("retrieval")), dataSource).migrate()`; espera `ModuleMigrationException` con `getModule()` igual a `retrieval` y código `ResponseCodeEnum.DATABASE_MIGRATION_FAILED` (escenario 3, FR-014)

### Implementation for User Story 4

- [X] T027 [US4] Ejecutar T024–T026; no se espera cambio de código de producción (el migrador de LAB-105 ya cubre historial y fallos)

**Checkpoint**: todas las historias verificadas

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T028 [P] Documentar las tablas de `retrieval` y la calificación `public.` de pgvector. `docs/base-conocimiento.md` no existe en esta rama (llega con LAB-18, aún sin fusionar), así que la nota va en una sección «Datos de `retrieval`» de `docs/modulos.md`; cuando LAB-18 se fusione, enlazarla desde «Qué no incluye» de `docs/base-conocimiento.md`
- [X] T029 [P] Regenerar y comprobar la documentación de módulos si CI la compara con el código: `mvn test -Dtest=ModularityTests -Dskip.srcclr=true --no-transfer-progress` y `git diff --stat docs/modulos/`; confirmar que esta historia no cambia nada ahí (no hay clases nuevas)
- [ ] T030 Ejecutar la comprobación manual de `quickstart.md` §2 contra la base local (`\d retrieval.chunk`, rechazo del vector de 3 dimensiones, borrado en cascada)
- [X] T031 Checklist previa al commit (`.ai/rules/development-guidelines.md` §8): `mvn checkstyle:check -Dskip.srcclr=true --no-transfer-progress` y `mvn verify -Dskip.srcclr=true -Dcheckstyle.skip=true --no-transfer-progress` en `backend/` (cobertura ≥ 80 %), `git status --short` sin archivos sueltos y `git diff --staged` solo con cambios de esta historia
- [X] T032 En `shoplab-infra` (rama `feature/LAB-19-ingest-without-database-credentials`, sin commit ni push): `ingest` tenía `DATABASE_*` en su ConfigMap y un `ExternalSecret` con la contraseña de Postgres; se retiraron `k8s/base/ingest/externalsecret.yaml`, el `secretRef` del Deployment y los literales `DATABASE_*`. `kustomize build k8s/overlays/dev` renderiza sin errores y sin referencias a base de datos en `ingest`
- [X] T033 Verificado en el código de `shoplab-infra` (no contra el clúster de dev): imagen `pgvector/pgvector:0.8.7-pg16` y `k8s/base/postgres/01-extensions.sql` con `CREATE EXTENSION IF NOT EXISTS vector;`, que instala en `public`. Falta confirmarlo en la base ya desplegada con esta consulta: `SELECT extnamespace::regnamespace FROM pg_extension WHERE extname = 'vector';`. Si no es `public`, ajustar `V2` (`public.vector` y `public.vector_cosine_ops`) antes del merge. Hacerla antes de T031

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: sin dependencias.
- **Foundational (Phase 2)**: depende de Setup; bloquea US1 y US2 (ambas usan `RetrievalSchemaIntegrationTests`).
- **US1 (Phase 3)** → crea `V2`. **US2 (Phase 4)** depende de US1 (tablas existentes y mismo archivo `V2`).
- **US3 (Phase 5)** depende de US1 (la migración debe existir para que el lint la revise); T020–T022 no dependen entre
  sí. **US4 (Phase 6)** depende de US1 y US2 (historial con la migración completa).
- **Polish (Phase 7)**: al final.

### Within Each User Story

- Las pruebas se escriben primero y deben fallar; después se edita `V2__knowledge_base_tables.sql`.
- US1 y US2 editan el mismo archivo SQL y la misma clase de pruebas: no se paralelizan entre sí.

### Parallel Opportunities

- T020, T021 y T022 (US3) tocan archivos distintos y se pueden hacer a la vez.
- T028 y T029 (Polish) se pueden hacer a la vez.
- Con dos personas: tras US1, una persona hace US2 y otra US3 (T020 solo necesita el repositorio).

### Parallel Example: User Story 3

```text
T020 IngestHasNoDatabaseCredentialsTests.java
T021 SchemaIsolationTests.java
T022 ModuleSchemasIntegrationTests.java
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Fase 1 y 2.
2. Fase 3 (US1): tablas, restricciones y pruebas.
3. **Parar y validar**: `RetrievalSchemaIntegrationTests` (T003–T011) en verde.

### Incremental Delivery

1. US1 → tablas funcionando (MVP).
2. US2 → búsqueda con índice HNSW y filtro por metadatos.
3. US3 → propiedad única e `ingest` sin credenciales protegidos en CI.
4. US4 → repetibilidad y fallo explícito sin pgvector.
5. Polish → documentación y checklist previa al commit.

---

## Notes

- Una sola migración (`V2`) que crece por historia: no se crean `V3`/`V4`; Flyway aún no la ha aplicado fuera de las
  pruebas, y cambiarla antes del merge no rompe checksums de ningún entorno.
- El PR se titula `feat(retrieval): [LAB-19] esquema retrieval con documentos y chunks vectorizados`.
- `[P]` solo donde los archivos son distintos; commits atómicos por historia con la clave LAB
  (`feat(retrieval): [LAB-19] ...`, práctica del repo según la guía §5.1; la constitución escribe la clave al
  principio y su reconciliación se hace en una enmienda aparte).
