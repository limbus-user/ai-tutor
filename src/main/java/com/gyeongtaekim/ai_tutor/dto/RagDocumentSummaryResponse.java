package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class RagDocumentSummaryResponse {
    private final Long id;
    private final String title;
    private final String storedFileName;
    private final String subject;
    private final String unitName;
    private final String trustLevel;
    private final LocalDateTime createdAt;

    public RagDocumentSummaryResponse(RagDocument document) {
        this.id = document.getId();
        this.title = document.getTitle();
        this.storedFileName = document.getStoredFileName();
        this.subject = document.getSubject();
        this.unitName = document.getUnitName();
        this.trustLevel = document.getTrustLevel();
        this.createdAt = document.getCreatedAt();
    }
}
