package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class ReviewQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wrong_answer_note_id")
    private WrongAnswerNote wrongAnswerNote;

    @Column(nullable = false, length = 500)
    private String referenceName;

    @Column(nullable = false)
    private LocalDateTime nextReviewAt;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueueStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ReviewQueue(User user, WrongAnswerNote wrongAnswerNote, String referenceName, LocalDateTime nextReviewAt, int priority) {
        this.user = user;
        this.wrongAnswerNote = wrongAnswerNote;
        this.referenceName = referenceName;
        this.nextReviewAt = nextReviewAt;
        this.priority = priority;
        this.status = QueueStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = QueueStatus.COMPLETED;
    }

    public enum QueueStatus {
        PENDING, COMPLETED
    }
}
