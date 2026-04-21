package com.gyeongtaekim.ai_tutor.service.question;

import java.util.List;

public record QuestionGenerationPlan(
        List<QuestionType> orderedTypes,
        String difficulty,
        String mode
) {
}
