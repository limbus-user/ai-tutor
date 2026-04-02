package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class ProblemCreateRequest {
    private String questionText;
    private String answer;
    private String explanation;
    private String difficulty;
    private String type;
    private List<Long> conceptIds;
}
