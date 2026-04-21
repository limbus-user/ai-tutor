package com.gyeongtaekim.ai_tutor.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.SessionQuiz;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class SessionQuizResponse {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Long id;
    private final Long sessionId;
    private final Long documentId;
    private final String quizSetId;
    private final String quizSetTitle;
    private final Integer order;
    private final String type;
    private final String question;
    private final List<String> choices;
    private final String correctAnswer;
    private final String modelAnswer;
    private final String explanation;
    private final String sourceEvidence;
    private final String difficulty;
    private final LocalDateTime createdAt;

    public SessionQuizResponse(SessionQuiz quiz) {
        this.id = quiz.getId();
        this.sessionId = quiz.getSession().getId();
        this.documentId = quiz.getDocumentId();
        this.quizSetId = quiz.getQuizSetId();
        this.quizSetTitle = quiz.getQuizSetTitle();
        this.order = quiz.getQuestionOrder();
        this.type = quiz.getType();
        this.question = quiz.getQuestion();
        this.choices = parseChoices(quiz.getChoicesJson());
        this.correctAnswer = quiz.getCorrectAnswer();
        this.modelAnswer = quiz.getModelAnswer();
        this.explanation = quiz.getExplanation();
        this.sourceEvidence = quiz.getSourceEvidence();
        this.difficulty = quiz.getDifficulty();
        this.createdAt = quiz.getCreatedAt();
    }

    private List<String> parseChoices(String choicesJson) {
        try {
            return OBJECT_MAPPER.readValue(choicesJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse quiz choices", e);
        }
    }
}
