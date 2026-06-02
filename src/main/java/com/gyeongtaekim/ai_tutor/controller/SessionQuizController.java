package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.SessionQuizResponse;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizSaveRequest;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizSubmitRequest;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizTitleUpdateRequest;
import com.gyeongtaekim.ai_tutor.service.SessionQuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat/sessions/{sessionId}/quizzes")
@RequiredArgsConstructor
public class SessionQuizController {

    private final SessionQuizService sessionQuizService;

    @GetMapping
    public ResponseEntity<List<SessionQuizResponse>> getQuizzes(@PathVariable Long sessionId) {
        return ResponseEntity.ok(sessionQuizService.getQuizzes(sessionId));
    }

    @PostMapping
    public ResponseEntity<List<SessionQuizResponse>> saveQuizzes(
            @PathVariable Long sessionId,
            @RequestBody SessionQuizSaveRequest request
    ) {
        return ResponseEntity.ok(sessionQuizService.saveQuizzes(sessionId, request));
    }

    @PatchMapping("/{quizSetId}")
    public ResponseEntity<List<SessionQuizResponse>> renameQuizSet(
            @PathVariable Long sessionId,
            @PathVariable String quizSetId,
            @RequestBody SessionQuizTitleUpdateRequest request
    ) {
        return ResponseEntity.ok(sessionQuizService.renameQuizSet(sessionId, quizSetId, request.getQuizSetTitle()));
    }

    @DeleteMapping("/{quizSetId}")
    public ResponseEntity<Void> deleteQuizSet(
            @PathVariable Long sessionId,
            @PathVariable String quizSetId
    ) {
        sessionQuizService.deleteQuizSet(sessionId, quizSetId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/submit")
    public ResponseEntity<SessionQuizResponse> submitQuiz(
            @PathVariable Long sessionId,
            @PathVariable Long quizId,
            @RequestBody SessionQuizSubmitRequest request
    ) {
        return ResponseEntity.ok(sessionQuizService.submitQuiz(sessionId, quizId, request));
    }

    @PostMapping("/{quizId}/reset")
    public ResponseEntity<SessionQuizResponse> resetQuiz(
            @PathVariable Long sessionId,
            @PathVariable Long quizId
    ) {
        return ResponseEntity.ok(sessionQuizService.resetQuiz(sessionId, quizId));
    }

    @PostMapping("/sets/{quizSetId}/reset")
    public ResponseEntity<List<SessionQuizResponse>> resetQuizSet(
            @PathVariable Long sessionId,
            @PathVariable String quizSetId
    ) {
        return ResponseEntity.ok(sessionQuizService.resetQuizSet(sessionId, quizSetId));
    }
}
