package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import lombok.Getter;

import java.util.List;

@Getter
public class RagDocumentUploadResponse {
    private final Long documentId;
    private final String title;
    private final String storedFileName;
    private final int chunkCount;
    private final String inferredSubject;
    private final String inferredUnit;
    private final List<String> recommendedTags;
    private final String documentDifficulty;
    private final String confidence;

    public RagDocumentUploadResponse(RagDocument document, int chunkCount) {
        this(document, chunkCount, document.getSubject(), document.getUnitName(), List.of(), "보통", "낮음");
    }

    public RagDocumentUploadResponse(
            RagDocument document,
            int chunkCount,
            String inferredSubject,
            String inferredUnit,
            List<String> recommendedTags,
            String documentDifficulty,
            String confidence
    ) {
        this.documentId = document.getId();
        this.title = document.getTitle();
        this.storedFileName = document.getStoredFileName();
        this.chunkCount = chunkCount;
        this.inferredSubject = inferredSubject;
        this.inferredUnit = inferredUnit;
        this.recommendedTags = recommendedTags;
        this.documentDifficulty = documentDifficulty;
        this.confidence = confidence;
    }
}
