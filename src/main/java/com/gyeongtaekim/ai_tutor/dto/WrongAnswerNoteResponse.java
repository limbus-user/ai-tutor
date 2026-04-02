package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.WrongAnswerNote;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Getter
public class WrongAnswerNoteResponse {
    private final Long id;
    private final Long userId;
    private final Long attemptId;
    private final List<String> conceptTags;
    private final String explanation;
    private final String reviewStatus;
    private final LocalDateTime createdAt;

    public WrongAnswerNoteResponse(WrongAnswerNote note) {
        this.id = note.getId();
        this.userId = note.getUser().getId();
        this.attemptId = note.getAttempt().getId();
        this.conceptTags = Arrays.stream(note.getConceptTags().split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .toList();
        this.explanation = note.getExplanation();
        this.reviewStatus = note.getReviewStatus().name();
        this.createdAt = note.getCreatedAt();
    }
}
