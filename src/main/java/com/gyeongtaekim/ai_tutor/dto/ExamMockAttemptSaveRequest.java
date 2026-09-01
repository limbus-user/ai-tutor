package com.gyeongtaekim.ai_tutor.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExamMockAttemptSaveRequest {
    private String attemptId;
    private String quizSetId;
    private String quizSetTitle;
    private JsonNode questions;
    private JsonNode results;
}
