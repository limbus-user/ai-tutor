package com.gyeongtaekim.ai_tutor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class SessionQuiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceDocumentIdsJson;

    @Column(nullable = false, length = 64)
    private String quizSetId;

    @Column(nullable = false)
    private String quizSetTitle;

    @Column(nullable = false)
    private Integer questionOrder;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String choicesJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String modelAnswer;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceEvidence;

    @Column(nullable = false)
    private String difficulty;

    @Column(nullable = false)
    private String conceptTag;

    @Column(nullable = false)
    private String understandingLevel;

    @Column(columnDefinition = "TEXT")
    private String submittedAnswer;

    private Boolean correct;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String evaluationFeedback;

    @Column(nullable = false)
    private Integer attemptCount;

    @Column(nullable = false)
    private Integer resetCount;

    @Column(nullable = false)
    private Boolean solved;

    private LocalDateTime lastSolvedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public SessionQuiz(
            ChatSession session,
            Long documentId,
            String sourceDocumentIdsJson,
            String quizSetId,
            String quizSetTitle,
            Integer questionOrder,
            String type,
            String question,
            String choicesJson,
            String correctAnswer,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty,
            String conceptTag,
            String understandingLevel
    ) {
        this.session = session;
        this.documentId = documentId;
        this.sourceDocumentIdsJson = sourceDocumentIdsJson;
        this.quizSetId = quizSetId;
        this.quizSetTitle = quizSetTitle;
        this.questionOrder = questionOrder;
        this.type = type;
        this.question = question;
        this.choicesJson = choicesJson;
        this.correctAnswer = correctAnswer;
        this.modelAnswer = modelAnswer;
        this.explanation = explanation;
        this.sourceEvidence = sourceEvidence;
        this.difficulty = difficulty;
        this.conceptTag = conceptTag;
        this.understandingLevel = understandingLevel;
        this.submittedAnswer = null;
        this.correct = null;
        this.evaluationFeedback = "";
        this.attemptCount = 0;
        this.resetCount = 0;
        this.solved = false;
        this.lastSolvedAt = null;
        this.createdAt = LocalDateTime.now();
    }

    public void updateQuizSetTitle(String quizSetTitle) {
        this.quizSetTitle = quizSetTitle;
    }

    public void submitResult(String submittedAnswer, boolean correct, String evaluationFeedback) {
        this.submittedAnswer = submittedAnswer;
        this.correct = correct;
        this.evaluationFeedback = evaluationFeedback == null ? "" : evaluationFeedback;
        this.attemptCount += 1;
        this.solved = true;
        this.lastSolvedAt = LocalDateTime.now();
    }

    public void resetProgress() {
        this.submittedAnswer = null;
        this.correct = null;
        this.evaluationFeedback = "";
        this.solved = false;
        this.resetCount += 1;
        this.lastSolvedAt = null;
    }
}
