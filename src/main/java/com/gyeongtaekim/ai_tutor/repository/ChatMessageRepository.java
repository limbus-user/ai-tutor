package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ChatMessage message where message.session.id = :sessionId")
    void deleteAllBySessionId(@Param("sessionId") Long sessionId);
}
