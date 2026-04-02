package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

@Getter
public class ChatSessionCreateRequest {
    private Long userId;
    private String title;
}
