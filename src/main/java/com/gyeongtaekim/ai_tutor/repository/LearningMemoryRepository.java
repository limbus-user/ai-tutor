package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.LearningMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LearningMemoryRepository extends JpaRepository<LearningMemory, Long> {
    Optional<LearningMemory> findByUserId(Long userId);
}
