package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.WrongAnswerDto;
import com.gyeongtaekim.ai_tutor.service.WrongAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wrong-answers")
@RequiredArgsConstructor
public class WrongAnswerController {

    private final WrongAnswerService wrongAnswerService;

    @PostMapping("/save")
    public ResponseEntity<String> saveWrongAnswer(@RequestBody WrongAnswerDto wrongAnswer) {
        try {
            wrongAnswerService.saveWrongAnswer(wrongAnswer);
            return ResponseEntity.ok("Wrong answer saved successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to save wrong answer: " + e.getMessage());
        }
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getWrongAnswers(@PathVariable Long userId) {
        try {
            List<Object> wrongAnswers = wrongAnswerService.getWrongAnswers(userId);
            return ResponseEntity.ok(wrongAnswers);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to get wrong answers: " + e.getMessage());
        }
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<String> clearWrongAnswers(@PathVariable Long userId) {
        try {
            wrongAnswerService.clearWrongAnswers(userId);
            return ResponseEntity.ok("Wrong answers cleared successfully");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to clear wrong answers: " + e.getMessage());
        }
    }
}
