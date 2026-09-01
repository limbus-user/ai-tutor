package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
public class ExamMockResponse {
    private final String quizSetId;
    private final String certification;
    private final String examName;
    private final LocalDate examDate;
    private final Integer questionCount;
    private final List<ExamMockQuestionResponse> questions;

    public ExamMockResponse(
            String quizSetId,
            String certification,
            String examName,
            LocalDate examDate,
            List<ExamMockQuestionResponse> questions
    ) {
        this.quizSetId = quizSetId;
        this.certification = certification;
        this.examName = examName;
        this.examDate = examDate;
        this.questionCount = questions.size();
        this.questions = questions;
    }
}
