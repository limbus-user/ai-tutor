package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import lombok.Getter;

@Getter
public class RagDocumentUploadResponse {
    private final Long documentId;
    private final String title;
    private final String storedFileName;
    private final int chunkCount;

    public RagDocumentUploadResponse(RagDocument document, int chunkCount) {
        this.documentId = document.getId();
        this.title = document.getTitle();
        this.storedFileName = document.getStoredFileName();
        this.chunkCount = chunkCount;
    }
}
