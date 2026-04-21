package com.gyeongtaekim.ai_tutor.service.question;

import java.util.List;
import java.util.Map;

public record QuestionDraft(
        QuestionType type,
        String question,
        List<String> choices,
        String correctAnswer,
        List<String> acceptableAnswers,
        String modelAnswer,
        String explanation,
        String sourceEvidence,
        String difficulty,
        List<String> tags,
        Map<String, Object> format,
        Map<String, Object> meta
) {
}
