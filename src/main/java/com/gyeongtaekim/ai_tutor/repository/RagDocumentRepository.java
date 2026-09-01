package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {
    Optional<RagDocument> findByStoredFileName(String storedFileName);
    Optional<RagDocument> findByStoredFileNameAndUserId(String storedFileName, Long userId);
    Optional<RagDocument> findByIdAndUserId(Long id, Long userId);
    List<RagDocument> findByUserIdOrderByCreatedAtDesc(Long userId);
}
