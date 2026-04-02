package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

@Getter
public class ChatMessageCreateRequest {
    private String role;
    private String content;
    private String sourceReferences;
}
