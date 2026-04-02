package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.LearningMemory;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class LearningMemoryResponse {
    private final Long id;
    private final Long userId;
    private final String weakConceptSummary;
    private final String historySummary;
    private final String preferences;
    private final LocalDateTime updatedAt;

    public LearningMemoryResponse(LearningMemory memory) {
        this.id = memory.getId();
        this.userId = memory.getUser().getId();
        this.weakConceptSummary = memory.getWeakConceptSummary();
        this.historySummary = memory.getHistorySummary();
        this.preferences = memory.getPreferences();
        this.updatedAt = memory.getUpdatedAt();
    }
}
