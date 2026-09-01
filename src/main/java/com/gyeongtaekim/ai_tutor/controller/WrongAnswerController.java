package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.WrongAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/wrong-answers")
@RequiredArgsConstructor
public class WrongAnswerController {

    private final WrongAnswerService wrongAnswerService;
    private final CurrentUserService currentUserService;

    @PostMapping("/save")
    public ResponseEntity<String> saveWrongAnswer(
            @RequestBody WrongAnswerDto wrongAnswer,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, wrongAnswer.getUserId());
            if (user != null) {
                wrongAnswer.setUserId(user.getId());
            }
            wrongAnswerService.saveWrongAnswer(wrongAnswer);
            return ResponseEntity.ok("Wrong answer saved successfully");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to save wrong answer: " + e.getMessage());
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getWrongAnswers(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            List<Object> wrongAnswers = wrongAnswerService.getWrongAnswers(user.getId());
            return ResponseEntity.ok(wrongAnswers);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to get wrong answers: " + e.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<String> clearWrongAnswers(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            wrongAnswerService.clearWrongAnswers(user.getId());
            return ResponseEntity.ok("Wrong answers cleared successfully");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to clear wrong answers: " + e.getMessage());
        }
    }
}
