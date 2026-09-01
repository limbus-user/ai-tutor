package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.ExamMockAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExamMockAttemptRepository extends JpaRepository<ExamMockAttempt, Long> {
    List<ExamMockAttempt> findByUserIdAndQuizSetIdOrderByCreatedAtDesc(Long userId, String quizSetId);
    Optional<ExamMockAttempt> findByUserIdAndAttemptId(Long userId, String attemptId);
}
