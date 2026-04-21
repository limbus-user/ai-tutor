package com.gyeongtaekim.ai_tutor.service.question;

import java.util.List;

public record QuestionValidationResult(
        boolean valid,
        List<String> errors,
        double answerLeakScore,
        double distractorQualityScore,
        double conceptCoverageScore,
        double difficultyFitScore
) {
}
