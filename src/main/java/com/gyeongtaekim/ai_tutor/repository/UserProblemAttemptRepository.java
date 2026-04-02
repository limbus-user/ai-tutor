package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.UserProblemAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProblemAttemptRepository extends JpaRepository<UserProblemAttempt, Long> {
}
