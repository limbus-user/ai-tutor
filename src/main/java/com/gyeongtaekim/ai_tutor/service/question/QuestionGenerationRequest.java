package com.gyeongtaekim.ai_tutor.service.question;

public record QuestionGenerationRequest(
        String type,
        int count,
        String difficulty,
        String mode,
        String distribution
) {
}
