package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.ChapterDto;
import com.gyeongtaekim.ai_tutor.dto.LearningSessionDto;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.LearningSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class LearningSessionController {

    private final LearningSessionService learningSessionService;
    private final CurrentUserService currentUserService;

    @GetMapping("/chapters")
    public ResponseEntity<List<ChapterDto>> getChapters() {
        try {
            List<ChapterDto> chapters = learningSessionService.getChapters();
            return ResponseEntity.ok(chapters);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/start")
    public ResponseEntity<LearningSessionDto> startSession(
            @RequestParam Long userId,
            @RequestParam String chapterName,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            LearningSessionDto session = learningSessionService.startSession(user.getId(), chapterName);
            return ResponseEntity.ok(session);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/questions/{userId}")
    public ResponseEntity<List<String>> getQuestions(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            List<String> questions = learningSessionService.generateQuestionsForSession(user.getId());
            return ResponseEntity.ok(questions);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/answer")
    public ResponseEntity<String> submitAnswer(
            @RequestParam Long userId,
            @RequestBody WrongAnswerDto wrongAnswer,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            wrongAnswer.setUserId(user.getId());
            String feedback = learningSessionService.submitAnswer(user.getId(), wrongAnswer);
            return ResponseEntity.ok(feedback);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/end/{userId}")
    public ResponseEntity<String> endSession(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            learningSessionService.endSession(user.getId());
            return ResponseEntity.ok("Session ended");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/chapters")
    public ResponseEntity<String> setChapters(@RequestBody List<ChapterDto> chapters) {
        try {
            learningSessionService.setChapters(chapters);
            return ResponseEntity.ok("Chapters set successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
