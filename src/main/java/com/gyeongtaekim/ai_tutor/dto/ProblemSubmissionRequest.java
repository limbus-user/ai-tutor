package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

@Getter
public class ProblemSubmissionRequest {
    private Long userId;
    private String submittedAnswer;
}
