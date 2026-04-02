package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

@Getter
public class LearningMemoryRequest {
    private String weakConceptSummary;
    private String historySummary;
    private String preferences;
}
