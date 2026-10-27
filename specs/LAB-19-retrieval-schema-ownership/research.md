# Research: Esquema retrieval propiedad del módulo retrieval

Sin `NEEDS CLARIFICATION` pendientes. Estas son las decisiones de diseño.

## D-1: El tipo `vector` se califica con `public.`

- **Contexto**: `ModuleSchemaMigrator` configura Flyway con `schemas(<módulo>)` y `defaultSchema(<módulo>)`, así que
  durante la migración `search_path` es solo `retrieval`. La extensión se instala en `public` (script
  `infra/postgres/init/01-extensions.sql` en local/dev). Un `vector(1024)` sin calificar no se resolvería. La imagen
  de Testcontainers (`SharedPostgres`) no ejecuta ese script: allí la extensión no existe de antemano.
- **Decisión**: la migración empieza con `CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;` y usa
  `public.vector(1024)` y `public.vector_cosine_ops`. No se usa `SET search_path` (prohibido por el lint S3).
- **Justificación**: funciona igual en las tres bases (local, dev, Testcontainers). `public` no es el esquema de ningún
  módulo, así que el lint S2 no lo marca. Si la extensión no está disponible, `CREATE EXTENSION` falla y el migrador
  aborta nombrando `retrieval` (FR-014).
- **Alternativas**: instalar la extensión en `retrieval` (la ata a un módulo y rompe en dev, donde ya está en
  `public`); `SET search_path` (prohibido); añadir un script de inicialización a Testcontainers (oculta el problema en
  producción).

## D-2: Identificadores `uuid`

- **Decisión**: `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` en ambas tablas.
- **Justificación**: otros módulos guardarán estos ids como referencia (citas en `chat`); un uuid no filtra volumen ni
  orden y se genera sin secuencia compartida. `gen_random_uuid()` es nativa desde PostgreSQL 13.
- **Alternativas**: `bigint` identity (más compacto, pero expone el orden y complica la extracción en F5); ids de
  texto como en Olist (los de Olist vienen del dataset; aquí los genera el sistema).

## D-3: Origen único del documento

- **Decisión**: `document.source text NOT NULL UNIQUE` (p. ej. `kb/returns-01-return-policy.md`).
- **Justificación**: cubre el caso «documento reingestado» del spec: la ingesta puede hacer un upsert por `source`
  sin duplicar. El título y los metadatos (tema, tipo, idioma, alcance) vienen del front matter del corpus (LAB-18).

## D-4: Posición del chunk

- **Decisión**: columna `chunk_index integer NOT NULL CHECK (chunk_index >= 0)` con `UNIQUE (document_id, chunk_index)`.
- **Justificación**: orden estable y reconstrucción del documento; `position` se evita por ser palabra con significado
  especial en SQL. La restricción única da FR-006 y un índice por `document_id` sin crear otro.

## D-5: Restricciones de la tabla `chunk`

- `embedding public.vector(1024) NOT NULL`: la dimensión la valida el propio tipo (FR-005).
- `embedding_model text NOT NULL CHECK (btrim(embedding_model) <> '')` (FR-003).
- `content text NOT NULL CHECK (content ~ '[^[:space:]]')` (edge case «texto vacío»).
- `document_id uuid NOT NULL REFERENCES document (id) ON DELETE CASCADE` (FR-007; la clave foránea es interna al
  esquema, permitida por ADR-0015).
- `metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object')` en ambas tablas (FR-004).

## D-6: Índice HNSW con coseno y parámetros por defecto

- **Decisión**: `CREATE INDEX chunk_embedding_hnsw_idx ON chunk USING hnsw (embedding public.vector_cosine_ops);`
  sin `WITH (m, ef_construction)`.
- **Justificación**: bge-m3 se compara por coseno; los valores por defecto (`m = 16`, `ef_construction = 64`) sobran
  para el tamaño del corpus. Ajustarlos exige datos de la evaluación (F2), no esta historia.
- **Alternativas**: IVFFlat (necesita datos previos para entrenar listas y rinde peor con pocas filas); distancia
  L2/producto interno (no coincide con el uso habitual del modelo).
- **Nota para consultas futuras**: el operador de coseno es `<=>`; la consulta debe ordenar por `embedding <=> :q`
  para usar el índice.
- **Hallazgo en la implementación**: un índice HNSW conserva las entradas de las filas borradas hasta un `VACUUM`, y
  una búsqueda que las recorre puede devolver menos filas de las que existen (se observó 1 de 3 tras insertar y
  borrar 10 000 chunks). En producción lo resuelve el autovacuum; las pruebas ejecutan `VACUUM retrieval.chunk` al
  limpiar. La historia de ingesta, que reemplaza los chunks de un documento al reingestarlo, debe tenerlo presente.

## D-7: Índice sobre metadatos del chunk

- **Decisión**: `CREATE INDEX chunk_metadata_gin_idx ON chunk USING gin (metadata jsonb_path_ops);`
- **Justificación**: FR-009 pide filtrar por atributo sin recorrer la tabla; `jsonb_path_ops` es más pequeño y rápido
  para el operador de contención `@>` (`metadata @> '{"topic":"returns"}'`), que es el caso de uso. No se indexan los
  metadatos de `document`: son pocas filas.
- **Alternativas**: GIN por defecto (`jsonb_ops`; admite más operadores que no se necesitan); columnas separadas por
  atributo (rompe la flexibilidad pedida).

## D-8: `updated_at` sin disparador

- **Decisión**: `document.created_at` y `updated_at` con `DEFAULT now()`; el módulo escribe `updated_at` al actualizar.
- **Justificación**: un disparador es lógica oculta en la base de datos y el resto de la arquitectura mantiene la
  lógica en la capa `application`. `chunk` solo lleva `created_at`: un chunk no se edita, se reemplaza.

## D-9: Cómo se prueba el índice y el rendimiento

- Existencia del índice: consulta a `pg_indexes`/`pg_index` (`indexdef` contiene `USING hnsw` y `vector_cosine_ops`).
  No depende del plan, porque con pocas filas el planificador prefiere un recorrido secuencial (edge case del spec).
- Uso del índice y 200 ms (SC-004): se cargan 10 000 chunks con vectores aleatorios generados en SQL
  (`generate_series`), se ejecuta `ANALYZE`, y en una transacción con `SET LOCAL enable_seqscan = off` se comprueba
  con `EXPLAIN` que el plan usa `chunk_embedding_hnsw_idx` y que la consulta de 5 vecinos tarda menos de 200 ms.
- Limpieza: las pruebas borran sus documentos al terminar (el contenedor es compartido por toda la JVM de pruebas).

## D-10: `ingest` sin credenciales de base de datos

- **Hoy**: `ingest/` solo tiene un `Dockerfile` con una página de marcador y no hay servicio `ingest` en
  `infra/docker-compose.yml`. El criterio se cumple, pero nada impide que se rompa.
- **Decisión**: una prueba (`IngestHasNoDatabaseCredentialsTests`) recorre `ingest/` y el bloque `ingest:` de
  `infra/docker-compose.yml` (si existe) y falla si encuentra variables, secretos o cadenas de conexión a base de datos
  (`DATABASE_URL`, `POSTGRES_*`, `SPRING_DATASOURCE_*`, `DB_HOST`, `jdbc:`, `postgresql://`…). Las pruebas de fixture
  de la propia prueba demuestran que detecta cada patrón.
- **Fuera de alcance**: el despliegue de `ingest` vive en el repositorio `shoplab-infra` (ADR-0006, ADR-0015); se
  anota como punto de revisión de esa historia, no se verifica desde aquí.
- **Alternativas**: workflow de CI aparte (más piezas para un chequeo de texto); no verificar (el criterio quedaría sin
  protección ante el primer `docker-compose` con `ingest`).
