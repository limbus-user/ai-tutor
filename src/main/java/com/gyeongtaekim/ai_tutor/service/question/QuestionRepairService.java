package com.gyeongtaekim.ai_tutor.service.question;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class QuestionRepairService {

    public QuestionDraft repair(QuestionDraft draft) {
        String question = draft.question() == null ? "" : draft.question().trim();
        String correctAnswer = draft.correctAnswer() == null ? "" : draft.correctAnswer().trim();
        List<String> choices = draft.choices() == null ? List.of() : new ArrayList<>(draft.choices());

        if (draft.type() == QuestionType.MULTIPLE_CHOICE || draft.type() == QuestionType.MULTI_SELECT) {
            question = stripAnswerLeak(question, correctAnswer);
            choices = new ArrayList<>(new LinkedHashSet<>(choices));
        }
        if (draft.type() == QuestionType.FILL_IN_BLANK) {
            question = stripAnswerLeak(question, correctAnswer);
            if (!question.contains("_____")) {
                question = question + " _____";
            }
        }

        return new QuestionDraft(
                draft.type(),
                question,
                choices,
                correctAnswer,
                draft.acceptableAnswers(),
                draft.modelAnswer(),
                draft.explanation(),
                draft.sourceEvidence(),
                draft.difficulty(),
                draft.tags(),
                draft.format() == null ? Map.of() : draft.format(),
                draft.meta() == null ? Map.of() : draft.meta()
        );
    }

    private String stripAnswerLeak(String question, String correctAnswer) {
        if (correctAnswer.isBlank()) {
            return question;
        }
        String escaped = java.util.regex.Pattern.quote(correctAnswer);
        return question.replaceAll("(?i)" + escaped, "_____").replaceAll("\\s{2,}", " ").trim();
    }
}
