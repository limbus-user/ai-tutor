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
    private LocalDateTime createdAt;

    public SessionQuiz(
            ChatSession session,
            Long documentId,
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
            String difficulty
    ) {
        this.session = session;
        this.documentId = documentId;
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
        this.createdAt = LocalDateTime.now();
    }

    public void updateQuizSetTitle(String quizSetTitle) {
        this.quizSetTitle = quizSetTitle;
    }
}
