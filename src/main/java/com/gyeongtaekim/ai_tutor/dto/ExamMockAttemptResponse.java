package com.gyeongtaekim.ai_tutor.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.ExamMockAttempt;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ExamMockAttemptResponse {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Long id;
    private final String attemptId;
    private final String quizSetId;
    private final String quizSetTitle;
    private final LocalDateTime createdAt;
    private final JsonNode questions;
    private final JsonNode results;

    public ExamMockAttemptResponse(ExamMockAttempt attempt) {
        this.id = attempt.getId();
        this.attemptId = attempt.getAttemptId();
        this.quizSetId = attempt.getQuizSetId();
        this.quizSetTitle = attempt.getQuizSetTitle();
        this.createdAt = attempt.getCreatedAt();
        this.questions = readJson(attempt.getQuestionsJson(), true);
        this.results = readJson(attempt.getResultsJson(), false);
    }

    private JsonNode readJson(String json, boolean arrayFallback) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            return arrayFallback ? OBJECT_MAPPER.createArrayNode() : OBJECT_MAPPER.createObjectNode();
        }
    }
}
