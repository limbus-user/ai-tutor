package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.ExamMockResponse;
import com.gyeongtaekim.ai_tutor.dto.ExamMockAttemptResponse;
import com.gyeongtaekim.ai_tutor.dto.ExamMockAttemptSaveRequest;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.ExamMockAttemptService;
import com.gyeongtaekim.ai_tutor.service.ExamMockService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/exam-mocks")
@RequiredArgsConstructor
public class ExamMockController {
    private final ExamMockService examMockService;
    private final ExamMockAttemptService examMockAttemptService;
    private final CurrentUserService currentUserService;

    @GetMapping("/it-engineer-20220424")
    public ResponseEntity<ExamMockResponse> getItEngineer20220424MockExam() {
        return ResponseEntity.ok(examMockService.getItEngineer20220424MockExam());
    }

    @GetMapping("/{quizSetId}")
    public ResponseEntity<ExamMockResponse> getMockExam(@PathVariable String quizSetId) {
        return ResponseEntity.ok(examMockService.getMockExam(quizSetId));
    }

    @GetMapping("/it-engineer-20220424/attempts")
    public ResponseEntity<List<ExamMockAttemptResponse>> getItEngineer20220424Attempts(Authentication authentication) {
        return getAttempts("it-engineer-20220424", authentication);
    }

    @GetMapping("/{quizSetId}/attempts")
    public ResponseEntity<List<ExamMockAttemptResponse>> getAttempts(
            @PathVariable String quizSetId,
            Authentication authentication
    ) {
        examMockService.getDefinition(quizSetId);
        User user = currentUserService.resolveUser(authentication, null);
        return ResponseEntity.ok(examMockAttemptService.getAttempts(user, quizSetId));
    }

    @PostMapping("/it-engineer-20220424/attempts")
    public ResponseEntity<ExamMockAttemptResponse> saveItEngineer20220424Attempt(
            @RequestBody ExamMockAttemptSaveRequest request,
            Authentication authentication
    ) {
        return saveAttempt("it-engineer-20220424", request, authentication);
    }

    @PostMapping("/{quizSetId}/attempts")
    public ResponseEntity<ExamMockAttemptResponse> saveAttempt(
            @PathVariable String quizSetId,
            @RequestBody ExamMockAttemptSaveRequest request,
            Authentication authentication
    ) {
        examMockService.getDefinition(quizSetId);
        User user = currentUserService.resolveUser(authentication, null);
        if (request.getQuizSetId() == null || request.getQuizSetId().isBlank()) {
            request.setQuizSetId(quizSetId);
        } else if (!quizSetId.equals(request.getQuizSetId())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "quizSetId does not match path");
        }
        return ResponseEntity.ok(examMockAttemptService.saveAttempt(user, request));
    }

    @DeleteMapping("/it-engineer-20220424/attempts/{attemptId}")
    public ResponseEntity<Void> deleteItEngineer20220424Attempt(
            @PathVariable String attemptId,
            Authentication authentication
    ) {
        return deleteAttempt("it-engineer-20220424", attemptId, authentication);
    }

    @DeleteMapping("/{quizSetId}/attempts/{attemptId}")
    public ResponseEntity<Void> deleteAttempt(
            @PathVariable String quizSetId,
            @PathVariable String attemptId,
            Authentication authentication
    ) {
        examMockService.getDefinition(quizSetId);
        User user = currentUserService.resolveUser(authentication, null);
        examMockAttemptService.deleteAttempt(user, quizSetId, attemptId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/it-engineer-20220424/media/{fileName:.+}")
    public ResponseEntity<Resource> getItEngineer20220424Media(@PathVariable String fileName) {
        return getMedia("it-engineer-20220424", fileName);
    }

    @GetMapping("/{quizSetId}/media/{fileName:.+}")
    public ResponseEntity<Resource> getMedia(
            @PathVariable String quizSetId,
            @PathVariable String fileName
    ) {
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new ResponseStatusException(NOT_FOUND, "Media not found");
        }

        ExamMockService.ExamMockDefinition definition = examMockService.getDefinition(quizSetId);
        Resource resource = new ClassPathResource(definition.resourceBase() + "/media/" + fileName);
        if (!resource.exists() || !resource.isReadable()) {
            throw new ResponseStatusException(NOT_FOUND, "Media not found");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .contentType(MediaType.IMAGE_GIF)
                .body(resource);
    }
}
