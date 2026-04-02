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
public class UserProblemAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(nullable = false, length = 1000)
    private String submittedAnswer;

    @Column(nullable = false)
    private boolean correct;

    @Column(nullable = false, length = 2000)
    private String feedback;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    public UserProblemAttempt(User user, Problem problem, String submittedAnswer, boolean correct, String feedback) {
        this.user = user;
        this.problem = problem;
        this.submittedAnswer = submittedAnswer;
        this.correct = correct;
        this.feedback = feedback;
        this.submittedAt = LocalDateTime.now();
    }
}
