package com.gyeongtaekim.ai_tutor.repository;

import com.gyeongtaekim.ai_tutor.domain.ExamQuestionBank;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExamQuestionBankRepository extends JpaRepository<ExamQuestionBank, Long> {
    boolean existsByCertificationAndExamDateAndQuestionNo(String certification, LocalDate examDate, Integer questionNo);
    long countByCertificationAndExamDate(String certification, LocalDate examDate);
    List<ExamQuestionBank> findByCertificationAndExamDateOrderByQuestionNoAsc(String certification, LocalDate examDate);
    Optional<ExamQuestionBank> findByCertificationAndExamDateAndQuestionNo(String certification, LocalDate examDate, Integer questionNo);
}
