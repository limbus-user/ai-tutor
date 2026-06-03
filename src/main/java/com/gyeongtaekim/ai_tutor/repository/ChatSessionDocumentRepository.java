package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.ChatSessionDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionDocumentRepository extends JpaRepository<ChatSessionDocument, Long> {
    List<ChatSessionDocument> findBySessionIdOrderByIdAsc(Long sessionId);
    boolean existsBySessionIdAndDocumentId(Long sessionId, Long documentId);
    void deleteAllBySessionId(Long sessionId);
    void deleteAllByDocumentId(Long documentId);
}
