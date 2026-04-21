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
    private final String correctAnswer;
    private final String explanation;
    private final String nextAction;
    private final LocalDateTime submittedAt;

    public ProblemSubmissionResponse(UserProblemAttempt attempt) {
        this.attemptId = attempt.getId();
        this.problemId = attempt.getProblem().getId();
        this.userId = attempt.getUser().getId();
        this.submittedAnswer = attempt.getSubmittedAnswer();
        this.correct = attempt.isCorrect();
        this.feedback = attempt.getFeedback();
        this.correctAnswer = attempt.getProblem().getAnswer();
        this.explanation = attempt.getProblem().getExplanation();
        this.nextAction = attempt.isCorrect()
                ? "다음 문제로 넘어가세요."
                : "오답노트에 저장되었어요. 해설을 읽고 다시 복습해 보세요.";
        this.submittedAt = attempt.getSubmittedAt();
    }
}
