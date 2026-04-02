package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.Concept;
import com.gyeongtaekim.ai_tutor.domain.Problem;
import lombok.Getter;

import java.util.List;

@Getter
public class ProblemResponse {
    private final Long id;
    private final String questionText;
    private final String answer;
    private final String explanation;
    private final String difficulty;
    private final String type;
    private final List<String> concepts;

    public ProblemResponse(Problem problem) {
        this.id = problem.getId();
        this.questionText = problem.getQuestionText();
        this.answer = problem.getAnswer();
        this.explanation = problem.getExplanation();
        this.difficulty = problem.getDifficulty().name();
        this.type = problem.getType().name();
        this.concepts = problem.getConcepts().stream().map(Concept::getName).toList();
    }
}
