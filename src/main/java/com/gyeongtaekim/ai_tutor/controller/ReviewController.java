package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.ReviewQueueResponse;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerNoteResponse;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final CurrentUserService currentUserService;

    @GetMapping("/wrong-answers/{userId}")
    public ResponseEntity<List<WrongAnswerNoteResponse>> getWrongAnswerNotes(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(reviewService.getWrongAnswerNotes(user.getId()));
    }

    @GetMapping("/queue/{userId}")
    public ResponseEntity<List<ReviewQueueResponse>> getReviewQueue(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(reviewService.getReviewQueue(user.getId()));
    }

    @PostMapping("/{reviewId}/complete")
    public ResponseEntity<ReviewQueueResponse> completeReview(
            @PathVariable Long reviewId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, null);
        return ResponseEntity.ok(reviewService.completeReview(reviewId, user));
    }
}
