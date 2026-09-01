package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_exam_mock_attempt_user_attempt", columnNames = {"user_id", "attempt_id"})
        }
)
@Getter
@NoArgsConstructor
public class ExamMockAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "attempt_id", nullable = false, length = 96)
    private String attemptId;

    @Column(nullable = false, length = 96)
    private String quizSetId;

    @Column(nullable = false)
    private String quizSetTitle;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionsJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String resultsJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ExamMockAttempt(
            User user,
            String attemptId,
            String quizSetId,
            String quizSetTitle,
            String questionsJson,
            String resultsJson
    ) {
        this.user = user;
        this.attemptId = attemptId;
        this.quizSetId = quizSetId;
        this.quizSetTitle = quizSetTitle;
        this.questionsJson = questionsJson;
        this.resultsJson = resultsJson;
        this.createdAt = LocalDateTime.now();
    }

    public void update(String quizSetTitle, String questionsJson, String resultsJson) {
        this.quizSetTitle = quizSetTitle;
        this.questionsJson = questionsJson;
        this.resultsJson = resultsJson;
    }
}
