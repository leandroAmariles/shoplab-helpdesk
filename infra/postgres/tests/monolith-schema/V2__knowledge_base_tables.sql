-- Tables of the 'retrieval' schema: the documents of the knowledge base and their vectorized chunks (specs/LAB-19).
-- Only the retrieval module reads and writes them. Other modules keep a document or chunk id and ask the module's API;
-- there is deliberately no foreign key or join across schemas.
--
-- Flyway runs this script with search_path = retrieval, so the pgvector type and operator class, installed in public,
-- are qualified. The extension is created here too, for databases that do not have it yet (a plain Postgres fails the
-- migration, and the start-up aborts naming this module).

CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

CREATE TABLE document (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    source     text        NOT NULL UNIQUE,
    title      text        NOT NULL,
    metadata   jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chunk (
    id              uuid                PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     uuid                NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index     integer             NOT NULL CHECK (chunk_index >= 0),
    content         text                NOT NULL CHECK (content ~ '[^[:space:]]'),
    embedding       public.vector(1024) NOT NULL,
    embedding_model text                NOT NULL CHECK (btrim(embedding_model) <> ''),
    metadata        jsonb               NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    created_at      timestamptz         NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

-- Nearest-neighbour search by cosine distance (ORDER BY embedding <=> query); default HNSW parameters.
CREATE INDEX chunk_embedding_hnsw_idx ON chunk USING hnsw (embedding public.vector_cosine_ops);

-- Filter chunks by metadata attribute with the containment operator (metadata @> '{"topic": "returns"}').
CREATE INDEX chunk_metadata_gin_idx ON chunk USING gin (metadata jsonb_path_ops);
