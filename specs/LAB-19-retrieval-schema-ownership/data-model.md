# Data Model: esquema `retrieval`

Esquema dueño: `retrieval` (módulo `retrieval`). Migración: `V2__knowledge_base_tables.sql`. Decisiones en
[research.md](research.md).

## `retrieval.document`

Un archivo fuente del corpus (p. ej. una política de `data/kb`).

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `uuid` | PK, `DEFAULT gen_random_uuid()` |
| `source` | `text` | `NOT NULL`, `UNIQUE` (referencia al origen; clave de reingesta) |
| `title` | `text` | `NOT NULL` |
| `metadata` | `jsonb` | `NOT NULL`, `DEFAULT '{}'`, `CHECK (jsonb_typeof(metadata) = 'object')` |
| `created_at` | `timestamptz` | `NOT NULL`, `DEFAULT now()` |
| `updated_at` | `timestamptz` | `NOT NULL`, `DEFAULT now()` (lo actualiza el módulo) |

Metadatos esperados (no impuestos por el esquema): `topic`, `type`, `language`, `scope`, tomados del front matter de
LAB-18.

## `retrieval.chunk`

Un fragmento de texto de un documento, con su vector.

| Columna | Tipo | Reglas |
|---|---|---|
| `id` | `uuid` | PK, `DEFAULT gen_random_uuid()` |
| `document_id` | `uuid` | `NOT NULL`, FK → `retrieval.document (id)` `ON DELETE CASCADE` |
| `chunk_index` | `integer` | `NOT NULL`, `CHECK (chunk_index >= 0)`; `UNIQUE (document_id, chunk_index)` |
| `content` | `text` | `NOT NULL`, `CHECK (content ~ '[^[:space:]]')` |
| `embedding` | `public.vector(1024)` | `NOT NULL` (la dimensión la valida el tipo) |
| `embedding_model` | `text` | `NOT NULL`, `CHECK (btrim(embedding_model) <> '')` |
| `metadata` | `jsonb` | `NOT NULL`, `DEFAULT '{}'`, `CHECK (jsonb_typeof(metadata) = 'object')` |
| `created_at` | `timestamptz` | `NOT NULL`, `DEFAULT now()` |

## Índices

| Índice | Definición | Requisito |
|---|---|---|
| `document_pkey`, `chunk_pkey` | PK | FR-001, FR-002 |
| `document_source_key` | `UNIQUE (source)` | FR-001, edge «reingesta» |
| `chunk_document_id_chunk_index_key` | `UNIQUE (document_id, chunk_index)` | FR-006 |
| `chunk_embedding_hnsw_idx` | `USING hnsw (embedding public.vector_cosine_ops)` | FR-008 |
| `chunk_metadata_gin_idx` | `USING gin (metadata jsonb_path_ops)` | FR-009 |

## Relaciones

- `document 1 — 0..n chunk` (dentro del esquema, con borrado en cascada).
- Referencias de otros módulos a un documento o chunk: solo por `id`, sin clave foránea (pueden quedar huérfanas).

## Transiciones de estado

No hay estados. Reingesta de un documento: upsert por `source` y sustitución de sus chunks (lógica de la historia de
ingesta, no de esta).
