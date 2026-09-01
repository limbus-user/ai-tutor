package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);
    List<DocumentChunk> findByDocumentIdAndDocumentUserIdOrderByChunkIndexAsc(Long documentId, Long userId);
    List<DocumentChunk> findByDocumentUserId(Long userId);
}
