package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
public class RagGeneratedQuestionResponse {
    private final Integer order;
    private final String type;
    private final String question;
    private final List<String> choices;
    private final String correctAnswer;
    private final String modelAnswer;
    private final String explanation;
    private final String sourceEvidence;
    private final String difficulty;
    private final String conceptTag;
    private final String understandingLevel;
    private final List<String> acceptableAnswers;
    private final List<String> tags;
    private final Map<String, Object> format;
    private final Map<String, Object> meta;

    public RagGeneratedQuestionResponse(
            Integer order,
            String type,
            String question,
            List<String> choices,
            String correctAnswer,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty,
            String conceptTag,
            String understandingLevel
    ) {
        this(
                order, type, question, choices, correctAnswer, List.of(modelAnswer), modelAnswer,
                explanation, sourceEvidence, difficulty, conceptTag, understandingLevel,
                List.of(conceptTag), Map.of(), Map.of()
        );
    }

    public RagGeneratedQuestionResponse(
            Integer order,
            String type,
            String question,
            List<String> choices,
            String correctAnswer,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty
    ) {
        this(order, type, question, choices, correctAnswer, modelAnswer, explanation, sourceEvidence,
                difficulty, "핵심 개념", "CONCEPT_UNDERSTANDING");
    }

    public RagGeneratedQuestionResponse(
            Integer order,
            String type,
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
        this(
                order, type, question, choices, correctAnswer, acceptableAnswers, modelAnswer,
                explanation, sourceEvidence, difficulty,
                tags.isEmpty() ? "핵심 개념" : tags.get(0),
                "CONCEPT_UNDERSTANDING", tags, format, meta
        );
    }

    private RagGeneratedQuestionResponse(
            Integer order,
            String type,
            String question,
            List<String> choices,
            String correctAnswer,
            List<String> acceptableAnswers,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty,
            String conceptTag,
            String understandingLevel,
            List<String> tags,
            Map<String, Object> format,
            Map<String, Object> meta
    ) {
        this.order = order;
        this.type = type;
        this.question = question;
        this.choices = choices;
        this.correctAnswer = correctAnswer;
        this.acceptableAnswers = acceptableAnswers;
        this.modelAnswer = modelAnswer;
        this.explanation = explanation;
        this.sourceEvidence = sourceEvidence;
        this.difficulty = difficulty;
        this.conceptTag = conceptTag;
        this.understandingLevel = understandingLevel;
        this.tags = tags;
        this.format = format;
        this.meta = meta;
    }

    public RagGeneratedQuestionResponse(Integer order, String question, String modelAnswer, String explanation) {
        this(
                order,
                "short_answer",
                question,
                List.of(),
                modelAnswer,
                modelAnswer,
                explanation,
                modelAnswer,
                "medium",
                "핵심 개념",
                "CONCEPT_UNDERSTANDING"
        );
    }
}
