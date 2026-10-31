-- A chunk may be stored before its vector exists (specs/LAB-20): the ingestion saves the chunks that the ingest service
-- returns and the embeddings story (LAB-21) completes them later. This relaxes the NOT NULL of V2 (LAB-19, FR-003)
-- without losing its traceability: a chunk has either both the vector and the model that produced it, or neither.
--
-- The HNSW index of V2 is untouched: pgvector does not index rows whose vector is null, and the nearest-neighbour
-- search filters on embedding IS NOT NULL.

ALTER TABLE chunk ALTER COLUMN embedding DROP NOT NULL;
ALTER TABLE chunk ALTER COLUMN embedding_model DROP NOT NULL;

ALTER TABLE chunk ADD CONSTRAINT chunk_embedding_pair_chk CHECK ((embedding IS NULL) = (embedding_model IS NULL));
