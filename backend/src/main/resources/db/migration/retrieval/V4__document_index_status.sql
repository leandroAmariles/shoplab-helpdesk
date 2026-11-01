-- Indexing state of a document (specs/LAB-21): the ingestion stores the chunks without vector (V3), the embeddings
-- step completes them and records here how it went. PENDING = waiting for its vectors (just ingested or ingested
-- again), INDEXED = every chunk has its vector, ERROR = the last attempt failed and index_error says why.
--
-- updated_at (touched by every ingestion) is the version of the content: the embeddings step only stores vectors when
-- it still matches the version it read, so a vector never lands on a chunk that was replaced meanwhile. Nothing here
-- touches the HNSW index of V2 or the tables of other modules.

ALTER TABLE document ADD COLUMN index_status text NOT NULL DEFAULT 'PENDING'
    CHECK (index_status IN ('PENDING', 'INDEXED', 'ERROR'));
ALTER TABLE document ADD COLUMN index_error text;
ALTER TABLE document ADD COLUMN indexed_at timestamptz;

ALTER TABLE document ADD CONSTRAINT document_index_state_chk CHECK (
    (index_status = 'ERROR') = (index_error IS NOT NULL)
    AND (index_status = 'INDEXED') = (indexed_at IS NOT NULL));

-- Documents stored before this migration: indexed when every chunk already has its vector, pending otherwise.
UPDATE document d SET index_status = 'INDEXED', indexed_at = now()
WHERE EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM chunk c WHERE c.document_id = d.id AND c.embedding IS NULL);
