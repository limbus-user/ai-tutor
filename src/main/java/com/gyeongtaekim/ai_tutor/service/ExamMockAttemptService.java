package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.ExamMockAttempt;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.ExamMockAttemptResponse;
import com.gyeongtaekim.ai_tutor.dto.ExamMockAttemptSaveRequest;
import com.gyeongtaekim.ai_tutor.repository.ExamMockAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExamMockAttemptService {

    private static final int MAX_ATTEMPTS_PER_EXAM = 10;

    private final ObjectMapper objectMapper;
    private final ExamMockAttemptRepository examMockAttemptRepository;

    public List<ExamMockAttemptResponse> getAttempts(User user, String quizSetId) {
        requireUser(user);
        return examMockAttemptRepository.findByUserIdAndQuizSetIdOrderByCreatedAtDesc(user.getId(), quizSetId)
                .stream()
                .limit(MAX_ATTEMPTS_PER_EXAM)
                .map(ExamMockAttemptResponse::new)
                .toList();
    }

    @Transactional
    public ExamMockAttemptResponse saveAttempt(User user, ExamMockAttemptSaveRequest request) {
        requireUser(user);
        if (request.getAttemptId() == null || request.getAttemptId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attemptId is required");
        }
        if (request.getQuizSetId() == null || request.getQuizSetId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quizSetId is required");
        }

        String questionsJson = writeJson(request.getQuestions(), "questions");
        String resultsJson = writeJson(request.getResults(), "results");
        String title = request.getQuizSetTitle() == null || request.getQuizSetTitle().isBlank()
                ? request.getQuizSetId()
                : request.getQuizSetTitle().trim();

        ExamMockAttempt attempt = examMockAttemptRepository.findByUserIdAndAttemptId(user.getId(), request.getAttemptId())
                .orElseGet(() -> new ExamMockAttempt(
                        user,
                        request.getAttemptId().trim(),
                        request.getQuizSetId().trim(),
                        title,
                        questionsJson,
                        resultsJson
                ));
        attempt.update(title, questionsJson, resultsJson);
        return new ExamMockAttemptResponse(examMockAttemptRepository.save(attempt));
    }

    @Transactional
    public void deleteAttempt(User user, String quizSetId, String attemptId) {
        requireUser(user);
        if (attemptId == null || attemptId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attemptId is required");
        }

        ExamMockAttempt attempt = examMockAttemptRepository.findByUserIdAndAttemptId(user.getId(), attemptId)
                .filter(item -> quizSetId.equals(item.getQuizSetId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attempt not found"));
        examMockAttemptRepository.delete(attempt);
    }

    private void requireUser(User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required");
        }
    }

    private String writeJson(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return "questions".equals(fieldName) ? "[]" : "{}";
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + fieldName);
        }
    }
}
