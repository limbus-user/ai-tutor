package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.ReviewQueue;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ReviewQueueResponse {
    private final Long id;
    private final Long userId;
    private final Long wrongAnswerNoteId;
    private final String referenceName;
    private final LocalDateTime nextReviewAt;
    private final int priority;
    private final String status;

    public ReviewQueueResponse(ReviewQueue queue) {
        this.id = queue.getId();
        this.userId = queue.getUser().getId();
        this.wrongAnswerNoteId = queue.getWrongAnswerNote().getId();
        this.referenceName = queue.getReferenceName();
        this.nextReviewAt = queue.getNextReviewAt();
        this.priority = queue.getPriority();
        this.status = queue.getStatus().name();
    }
}
