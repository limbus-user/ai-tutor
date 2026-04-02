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
public class WrongAnswerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id")
    private UserProblemAttempt attempt;

    @Column(nullable = false, length = 500)
    private String conceptTags;

    @Column(nullable = false, length = 2000)
    private String explanation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus reviewStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public WrongAnswerNote(User user, UserProblemAttempt attempt, String conceptTags, String explanation) {
        this.user = user;
        this.attempt = attempt;
        this.conceptTags = conceptTags;
        this.explanation = explanation;
        this.reviewStatus = ReviewStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void markReviewed() {
        this.reviewStatus = ReviewStatus.REVIEWED;
    }

    public enum ReviewStatus {
        PENDING, REVIEWED
    }
}
