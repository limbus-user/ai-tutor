ALTER TABLE document_chunk
    ALTER COLUMN chunk_text TYPE TEXT;

ALTER TABLE rag_document
    ALTER COLUMN extracted_text TYPE TEXT;

ALTER TABLE chat_message
    ALTER COLUMN content TYPE TEXT;
