package com.gyeongtaekim.ai_tutor.service.question;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class QuestionValidator {

    private static final List<String> INCOMPLETE_ENDINGS = List.of(
            "\uB2E4\uC74C\uACFC \uAC19\uC74C", // 다음과 같음
            "\uB2E4\uC74C\uACFC \uAC19\uB2E4", // 다음과 같다
            "\uC544\uB798\uC640 \uAC19\uC74C", // 아래와 같음
            "\uC544\uB798\uC640 \uAC19\uB2E4", // 아래와 같다
            "\uC8FC\uC694 \uC5ED\uD560\uC740", // 주요 역할은
            "\uC5ED\uD560\uC740", // 역할은
            "\uD2B9\uC9D5\uC740", // 특징은
            "\uC885\uB958\uB294", // 종류는
            "\uC608\uC2DC\uB294", // 예시는
            "\uB85C\uC758 \uC8FC\uC694 \uC5ED\uD560\uC740" // 로의 주요 역할은
    );
    private static final List<String> BAD_FINAL_PARTICLES = List.of(
            "\uC740", "\uB294", "\uC774", "\uAC00", "\uC744", "\uB97C", "\uC758",
            "\uB85C", "\uC73C\uB85C", "\uC640", "\uACFC", "\uC5D0\uAC8C", "\uC5D0\uC11C", "\uCC98\uB7FC", "\uBC0F"
    );

    public QuestionValidationResult validate(QuestionDraft draft) {
        List<String> errors = new ArrayList<>();
        double answerLeakScore = 1.0;
        double distractorQualityScore = 1.0;
        double conceptCoverageScore = 1.0;
        double difficultyFitScore = 1.0;

        if (isBlank(draft.question()) || isBlank(draft.correctAnswer()) || isBlank(draft.explanation()) || isBlank(draft.sourceEvidence())) {
            errors.add("required fields are missing");
        }

        if (draft.type() != QuestionType.SHORT_ANSWER
                && normalize(draft.explanation()).equals(normalize(draft.correctAnswer()))) {
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
            case SHORT_ANSWER -> {
                String concept = inferConcept(draft);
                String answer = bestAnswer(draft);
                String question = draft.question() == null ? "" : draft.question();

                if (isBlank(concept)) {
                    errors.add("short_answer requires a concept tag");
                    conceptCoverageScore = 0.2;
                }
                if (answer.length() < 25) {
                    errors.add("short_answer answer is too short");
                    conceptCoverageScore = 0.4;
                }
                if (!containsConcept(question, concept) || !containsConcept(answer, concept)) {
                    errors.add("short_answer question and answer must focus on the same concept");
                    conceptCoverageScore = 0.2;
                }
                if (isIncompleteShortAnswer(answer)) {
                    errors.add("short_answer answer is an incomplete Korean sentence");
                    conceptCoverageScore = 0.2;
                }
                if (requiresApplication(question) && !hasConcreteSituation(answer)) {
                    errors.add("short_answer application answer requires a concrete situation or example");
                    difficultyFitScore = 0.3;
                }
                if (requiresExample(question) && !hasExampleAndReason(answer)) {
                    errors.add("short_answer example answer requires an example and reason");
                    difficultyFitScore = 0.4;
                }
                if (requiresComparison(question) && !hasComparisonSignal(answer)) {
                    errors.add("short_answer comparison answer requires a difference");
                    difficultyFitScore = 0.4;
                }
                if (requiresExplanation(question) && !hasDefinitionSignal(answer)) {
                    errors.add("short_answer explanation answer requires definition and core characteristic");
                    difficultyFitScore = 0.5;
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

    private String bestAnswer(QuestionDraft draft) {
        if (!isBlank(draft.modelAnswer())) {
            return draft.modelAnswer().trim();
        }
        return draft.correctAnswer() == null ? "" : draft.correctAnswer().trim();
    }

    private String inferConcept(QuestionDraft draft) {
        Object concept = draft.meta() == null ? null : draft.meta().get("concept");
        if (concept instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        if (draft.tags() != null) {
            return draft.tags().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .filter(value -> !Set.of("short-answer", "concept-check", "application", "comparison").contains(normalize(value)))
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private boolean containsConcept(String text, String concept) {
        if (isBlank(concept)) {
            return true;
        }
        String normalizedText = normalize(text).replaceAll("\\s+", "");
        String normalizedConcept = normalize(concept).replaceAll("\\s+", "");
        return normalizedText.contains(normalizedConcept);
    }

    private boolean isIncompleteShortAnswer(String answer) {
        String trimmed = answer == null ? "" : answer.trim();
        if (trimmed.isBlank()) {
            return true;
        }
        String normalized = trimmed.replaceAll("\\s+", " ");
        boolean badEnding = INCOMPLETE_ENDINGS.stream().anyMatch(normalized::endsWith)
                || BAD_FINAL_PARTICLES.stream().anyMatch(normalized::endsWith);
        boolean awkwardGrammar = normalized.matches(".*(\uC740\\s+[^.?!]{0,30}\\s+\uC740|\uB294\\s+[^.?!]{0,30}\\s+\uB294).*")
                || normalized.contains("\uB85C\uC758 \uC8FC\uC694 \uC5ED\uD560");
        return badEnding || awkwardGrammar || normalized.split("\\s+").length < 5;
    }

    private boolean requiresApplication(String question) {
        String normalized = normalize(question);
        return normalized.contains("\uC801\uC6A9") || normalized.contains("\uC0C1\uD669") || normalized.contains("\uC2E4\uC81C");
    }

    private boolean requiresExample(String question) {
        String normalized = normalize(question);
        return normalized.contains("\uC608\uC2DC") || normalized.contains("\uC0AC\uB840");
    }

    private boolean requiresComparison(String question) {
        String normalized = normalize(question);
        return normalized.contains("\uBE44\uAD50") || normalized.contains("\uCC28\uC774");
    }

    private boolean requiresExplanation(String question) {
        String normalized = normalize(question);
        return normalized.contains("\uC124\uBA85") && !requiresApplication(question) && !requiresExample(question) && !requiresComparison(question);
    }

    private boolean hasConcreteSituation(String answer) {
        String normalized = normalize(answer);
        return normalized.contains("\uC608\uB97C \uB4E4\uC5B4")
                || normalized.contains("\uC608\uB97C \uB4E4\uBA74")
                || normalized.contains("\uC0C1\uD669")
                || normalized.contains("\uC0AC\uC6A9\uC790\uAC00")
                || normalized.contains("\uD504\uB85C\uADF8\uB7A8")
                || normalized.contains("\uD30C\uC77C")
                || normalized.contains("\uBA54\uBAA8\uB9AC")
                || normalized.contains("\uC694\uCCAD")
                || normalized.contains("\uB54C ");
    }

    private boolean hasExampleAndReason(String answer) {
        String normalized = normalize(answer);
        return (normalized.contains("\uC608\uB97C \uB4E4\uC5B4") || normalized.contains("\uC608\uC2DC") || normalized.contains("\uC0AC\uB840"))
                && (normalized.contains("\uB54C\uBB38") || normalized.contains("\uC774\uC720") || normalized.contains("\uD574\uB2F9"));
    }

    private boolean hasComparisonSignal(String answer) {
        String normalized = normalize(answer);
        return normalized.contains("\uCC28\uC774")
                || normalized.contains("\uBC18\uBA74")
                || normalized.contains("\uB2E4\uB974")
                || normalized.contains("\uAD6C\uBD84");
    }

    private boolean hasDefinitionSignal(String answer) {
        String normalized = normalize(answer);
        return normalized.contains("\uC740 ") || normalized.contains("\uB294 ") || normalized.contains("\uC758\uBBF8") || normalized.contains("\uAC1C\uB150");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
