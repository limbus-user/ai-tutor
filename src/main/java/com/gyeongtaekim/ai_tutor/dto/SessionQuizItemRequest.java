package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class SessionQuizItemRequest {
    private Integer order;
    private String type;
    private String question;
    private List<String> choices;
    private String correctAnswer;
    private String modelAnswer;
    private String explanation;
    private String sourceEvidence;
    private String difficulty;
}
