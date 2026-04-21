package com.gyeongtaekim.ai_tutor.service.question;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class QuestionValidator {

    public QuestionValidationResult validate(QuestionDraft draft) {
        List<String> errors = new ArrayList<>();
        double answerLeakScore = 1.0;
        double distractorQualityScore = 1.0;
        double conceptCoverageScore = 1.0;
        double difficultyFitScore = 1.0;

        if (isBlank(draft.question()) || isBlank(draft.correctAnswer()) || isBlank(draft.explanation()) || isBlank(draft.sourceEvidence())) {
            errors.add("required fields are missing");
        }

        if (normalize(draft.explanation()).equals(normalize(draft.correctAnswer()))) {
            errors.add("explanation only repeats the answer");
            conceptCoverageScore = 0.3;
        }

        switch (draft.type()) {
            case MULTIPLE_CHOICE -> {
                if (containsAnswerLeak(draft.question(), draft.correctAnswer())) {
                    errors.add("multiple_choice stem leaks the answer");
                    answerLeakScore = 0.0;
                }
                if (draft.choices() == null || draft.choices().size() != 4) {
                    errors.add("multiple_choice must have 4 choices");
                }
                if (hasDuplicates(draft.choices())) {
                    errors.add("multiple_choice choices must be unique");
                    distractorQualityScore = 0.2;
                }
                if (draft.choices() == null || !draft.choices().contains(draft.correctAnswer())) {
                    errors.add("multiple_choice correctAnswer must match one of the choices");
                }
            }
            case FILL_IN_BLANK -> {
                if (!draft.question().contains("_____")) {
                    errors.add("fill_in_blank must contain a blank");
                }
                if (containsAnswerLeak(draft.question(), draft.correctAnswer())) {
                    errors.add("fill_in_blank stem leaks the answer");
                    answerLeakScore = 0.0;
                }
            }
            case TRUE_FALSE -> {
                String normalized = normalize(draft.correctAnswer());
                if (!Set.of("true", "false", "o", "x").contains(normalized)) {
                    errors.add("true_false correctAnswer must be true/false or O/X");
                }
            }
            case MATCHING -> {
                int leftSize = asListSize(draft.format(), "leftItems");
                int rightSize = asListSize(draft.format(), "rightItems");
                if (leftSize == 0 || leftSize != rightSize) {
                    errors.add("matching requires equal non-empty left/right items");
                }
            }
            case ORDERING -> {
                if (asListSize(draft.format(), "items") < 3) {
                    errors.add("ordering requires at least 3 items");
                }
            }
            case CODE_READING, CODE_COMPLETION, ERROR_DETECTION -> {
                if (!draft.question().contains("```")) {
                    errors.add("code question must include a code block");
                }
            }
            case COMPARISON -> {
                Object compareTargets = draft.meta().get("compareTargets");
                if (!(compareTargets instanceof List<?> list) || list.size() != 2) {
                    errors.add("comparison requires exactly 2 compare targets");
                }
            }
            case MULTI_SELECT -> {
                if (draft.choices() == null || draft.choices().size() != 4) {
                    errors.add("multi_select must have 4 choices");
                }
                if (draft.acceptableAnswers() == null || draft.acceptableAnswers().size() < 2) {
                    errors.add("multi_select requires multiple acceptable answers");
                }
            }
            default -> {
            }
        }

        return new QuestionValidationResult(errors.isEmpty(), errors, answerLeakScore, distractorQualityScore, conceptCoverageScore, difficultyFitScore);
    }

    private boolean containsAnswerLeak(String question, String correctAnswer) {
        return !isBlank(question) && !isBlank(correctAnswer)
                && normalize(question).contains(normalize(correctAnswer));
    }

    private boolean hasDuplicates(List<String> values) {
        if (values == null) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private int asListSize(java.util.Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? list.size() : 0;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
