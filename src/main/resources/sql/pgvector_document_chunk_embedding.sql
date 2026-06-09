CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE document_chunk
ADD COLUMN IF NOT EXISTS embedding vector(1536);

CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding
ON document_chunk
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
