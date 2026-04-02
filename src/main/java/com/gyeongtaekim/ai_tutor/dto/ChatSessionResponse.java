package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ChatSessionResponse {
    private final Long id;
    private final Long userId;
    private final String title;
    private final String status;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public ChatSessionResponse(ChatSession session) {
        this.id = session.getId();
        this.userId = session.getUser().getId();
        this.title = session.getTitle();
        this.status = session.getStatus().name();
        this.createdAt = session.getCreatedAt();
        this.updatedAt = session.getUpdatedAt();
    }
}
