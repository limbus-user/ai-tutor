package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.ReviewQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewQueueRepository extends JpaRepository<ReviewQueue, Long> {
    List<ReviewQueue> findByUserIdOrderByNextReviewAtAsc(Long userId);
    Optional<ReviewQueue> findByIdAndUserId(Long id, Long userId);
}
