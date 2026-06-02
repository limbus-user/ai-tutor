package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class RagGeneratedQuestionResponse {
    private Integer order;
    private String type;
    private String question;
    private List<String> choices;
    private String correctAnswer;
    private String modelAnswer;
    private String explanation;
    private String sourceEvidence;
    private String difficulty;
    private String conceptTag;
    private String understandingLevel;

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
