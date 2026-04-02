package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.WrongAnswerNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WrongAnswerNoteRepository extends JpaRepository<WrongAnswerNote, Long> {
    List<WrongAnswerNote> findByUserIdOrderByCreatedAtDesc(Long userId);
}
