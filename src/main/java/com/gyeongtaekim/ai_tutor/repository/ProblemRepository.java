package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.Problem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemRepository extends JpaRepository<Problem, Long> {
}
