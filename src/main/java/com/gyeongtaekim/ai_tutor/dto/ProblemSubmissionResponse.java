package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.UserProblemAttempt;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ProblemSubmissionResponse {
    private final Long attemptId;
    private final Long problemId;
    private final Long userId;
    private final String submittedAnswer;
    private final boolean correct;
    private final String feedback;
    private final LocalDateTime submittedAt;

    public ProblemSubmissionResponse(UserProblemAttempt attempt) {
        this.attemptId = attempt.getId();
        this.problemId = attempt.getProblem().getId();
        this.userId = attempt.getUser().getId();
        this.submittedAnswer = attempt.getSubmittedAnswer();
        this.correct = attempt.isCorrect();
        this.feedback = attempt.getFeedback();
        this.submittedAt = attempt.getSubmittedAt();
    }
}
