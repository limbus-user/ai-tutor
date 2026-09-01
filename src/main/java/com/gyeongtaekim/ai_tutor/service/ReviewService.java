package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.Problem;
import com.gyeongtaekim.ai_tutor.domain.ReviewQueue;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.domain.UserProblemAttempt;
import com.gyeongtaekim.ai_tutor.domain.WrongAnswerNote;
import com.gyeongtaekim.ai_tutor.dto.ReviewQueueResponse;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerNoteResponse;
import com.gyeongtaekim.ai_tutor.repository.ReviewQueueRepository;
import com.gyeongtaekim.ai_tutor.repository.WrongAnswerNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final WrongAnswerNoteRepository wrongAnswerNoteRepository;
    private final ReviewQueueRepository reviewQueueRepository;

    public void recordWrongAnswer(User user, Problem problem, UserProblemAttempt attempt) {
        String conceptTags = problem.getConcepts().isEmpty()
                ? "uncategorized"
                : problem.getConcepts().stream().map(concept -> concept.getName()).distinct().reduce((a, b) -> a + ", " + b).orElse("uncategorized");

        String explanation = "오답 복습 노트: " + problem.getExplanation();
        WrongAnswerNote note = wrongAnswerNoteRepository.save(
                new WrongAnswerNote(user, attempt, conceptTags, explanation)
        );

        ReviewQueue reviewQueue = new ReviewQueue(
                user,
                note,
                problem.getQuestionText(),
                LocalDateTime.now().plusDays(1),
                1
        );
        reviewQueueRepository.save(reviewQueue);
    }

    public List<WrongAnswerNoteResponse> getWrongAnswerNotes(Long userId) {
        return wrongAnswerNoteRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(WrongAnswerNoteResponse::new)
                .toList();
    }

    public List<ReviewQueueResponse> getReviewQueue(Long userId) {
        return reviewQueueRepository.findByUserIdOrderByNextReviewAtAsc(userId).stream()
                .map(ReviewQueueResponse::new)
                .toList();
    }

    public ReviewQueueResponse completeReview(Long reviewId) {
        return completeReview(reviewId, null);
    }

    public ReviewQueueResponse completeReview(Long reviewId, User currentUser) {
        ReviewQueue queue = (currentUser == null
                ? reviewQueueRepository.findById(reviewId)
                : reviewQueueRepository.findByIdAndUserId(reviewId, currentUser.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review item not found"));
        queue.complete();
        queue.getWrongAnswerNote().markReviewed();
        wrongAnswerNoteRepository.save(queue.getWrongAnswerNote());
        return new ReviewQueueResponse(reviewQueueRepository.save(queue));
    }
}
