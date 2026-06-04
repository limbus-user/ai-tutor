package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FeedbackRequest {
    private String email;
    private String category;
    private String message;
}
