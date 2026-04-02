package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.ChapterDto;
import com.gyeongtaekim.ai_tutor.dto.LearningSessionDto;
import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import com.gyeongtaekim.ai_tutor.service.LearningSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class LearningSessionController {

    private final LearningSessionService learningSessionService;

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
    public ResponseEntity<LearningSessionDto> startSession(@RequestParam Long userId, @RequestParam String chapterName) {
        try {
            LearningSessionDto session = learningSessionService.startSession(userId, chapterName);
            return ResponseEntity.ok(session);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/questions/{userId}")
    public ResponseEntity<List<String>> getQuestions(@PathVariable Long userId) {
        try {
            List<String> questions = learningSessionService.generateQuestionsForSession(userId);
            return ResponseEntity.ok(questions);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/answer")
    public ResponseEntity<String> submitAnswer(@RequestParam Long userId, @RequestBody WrongAnswerDto wrongAnswer) {
        try {
            String feedback = learningSessionService.submitAnswer(userId, wrongAnswer);
            return ResponseEntity.ok(feedback);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/end/{userId}")
    public ResponseEntity<String> endSession(@PathVariable Long userId) {
        try {
            learningSessionService.endSession(userId);
            return ResponseEntity.ok("Session ended");
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
