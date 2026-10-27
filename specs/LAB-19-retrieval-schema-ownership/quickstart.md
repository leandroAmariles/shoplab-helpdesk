# Quickstart: validar LAB-19

Guía para comprobar de punta a punta que el esquema `retrieval` está bien. Contrato en
[contracts/retrieval-schema.md](contracts/retrieval-schema.md); tablas en [data-model.md](data-model.md).

## Requisitos

- Docker en marcha (Testcontainers levanta `pgvector/pgvector:0.8.7-pg16`).
- JDK 21 y Maven (desde `backend/`).

## 1. Pruebas automáticas

```bash
cd backend
mvn test -Dtest='RetrievalSchemaIntegrationTests,ModuleSchemasIntegrationTests,SchemaIsolationTests,IngestHasNoDatabaseCredentialsTests' \
  -Dskip.srcclr=true --no-transfer-progress
```

Resultado esperado: todas en verde. Cubren los escenarios de las historias 1 a 4 del spec, el rendimiento (SC-004) y
el aislamiento (SC-005, SC-006).

Suite completa antes del commit (pre-commit checklist):

```bash
mvn verify -Dskip.srcclr=true --no-transfer-progress
```

## 2. Comprobación manual contra la base local

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend && mvn spring-boot:run   # aplica V2 al arrancar
```

En `psql`:

```sql
\dt retrieval.*                              -- document, chunk, flyway_schema_history
\d retrieval.chunk                           -- embedding vector(1024), embedding_model NOT NULL, índices
SELECT version, success FROM retrieval.flyway_schema_history ORDER BY installed_rank;   -- 1 y 2, success = t
SELECT indexdef FROM pg_indexes WHERE schemaname = 'retrieval' AND tablename = 'chunk';  -- hnsw y gin
```

Rechazos esperados:

```sql
INSERT INTO retrieval.document (source, title) VALUES ('tmp/a.md', 'A') RETURNING id;
INSERT INTO retrieval.chunk (document_id, chunk_index, content, embedding, embedding_model)
VALUES ('<id>', 0, 'texto', '[1,2,3]', 'bge-m3');   -- ERROR: expected 1024 dimensions, not 3
DELETE FROM retrieval.document WHERE source = 'tmp/a.md';
```

## 3. Segundo arranque

Arrancar el backend otra vez: el log muestra `Schema 'retrieval' ready: 0 migration(s) applied` y los datos siguen
ahí.

## 4. Base sin pgvector (fallo esperado)

Con una PostgreSQL sin la extensión disponible, el arranque aborta con `ModuleMigrationException` que nombra el módulo
`retrieval` (FR-014). Se cubre en la suite con un contenedor `postgres:16` desechable.
