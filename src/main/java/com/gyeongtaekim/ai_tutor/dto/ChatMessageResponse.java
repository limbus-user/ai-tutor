package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.ChatMessage;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ChatMessageResponse {
    private final Long id;
    private final Long sessionId;
    private final String role;
    private final String content;
    private final String sourceReferences;
    private final LocalDateTime createdAt;

    public ChatMessageResponse(ChatMessage message) {
        this.id = message.getId();
        this.sessionId = message.getSession().getId();
        this.role = message.getRole().name();
        this.content = message.getContent();
        this.sourceReferences = message.getSourceReferences();
        this.createdAt = message.getCreatedAt();
    }
}
