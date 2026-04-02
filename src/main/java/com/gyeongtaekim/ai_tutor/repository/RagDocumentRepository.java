package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {
    Optional<RagDocument> findByStoredFileName(String storedFileName);
}
