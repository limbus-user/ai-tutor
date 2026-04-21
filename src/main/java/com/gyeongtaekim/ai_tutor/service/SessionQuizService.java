package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.SessionQuiz;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizItemRequest;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizResponse;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizSaveRequest;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.SessionQuizRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionQuizService {

    private final ObjectMapper objectMapper;
    private final SessionQuizRepository sessionQuizRepository;
    private final ChatSessionRepository chatSessionRepository;

    public List<SessionQuizResponse> getQuizzes(Long sessionId) {
        findSession(sessionId);
        return sessionQuizRepository.findBySessionIdOrderByCreatedAtAscQuestionOrderAsc(sessionId).stream()
                .map(SessionQuizResponse::new)
                .toList();
    }

    @Transactional
    public List<SessionQuizResponse> saveQuizzes(Long sessionId, SessionQuizSaveRequest request) {
        if (request.getDocumentId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId is required");
        }
        if (request.getQuestions() == null || request.getQuestions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "questions are required");
        }

        ChatSession session = findSession(sessionId);
        String quizSetId = UUID.randomUUID().toString();
        String quizSetTitle = request.getQuizSetTitle() == null || request.getQuizSetTitle().isBlank()
                ? "Quiz Set " + quizSetId.substring(0, 8)
                : request.getQuizSetTitle().trim();

        List<SessionQuiz> saved = sessionQuizRepository.saveAll(request.getQuestions().stream()
                .map(question -> toEntity(session, request.getDocumentId(), quizSetId, quizSetTitle, question))
                .toList());

        return saved.stream()
                .map(SessionQuizResponse::new)
                .toList();
    }

    @Transactional
    public List<SessionQuizResponse> renameQuizSet(Long sessionId, String quizSetId, String quizSetTitle) {
        if (quizSetTitle == null || quizSetTitle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quizSetTitle is required");
        }

        findSession(sessionId);
        List<SessionQuiz> quizzes = sessionQuizRepository.findBySessionIdAndQuizSetIdOrderByCreatedAtAscQuestionOrderAsc(sessionId, quizSetId);
        if (quizzes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz set not found");
        }

        String trimmedTitle = quizSetTitle.trim();
        quizzes.forEach(quiz -> quiz.updateQuizSetTitle(trimmedTitle));
        return sessionQuizRepository.saveAll(quizzes).stream()
                .map(SessionQuizResponse::new)
                .toList();
    }

    @Transactional
    public void deleteQuizSet(Long sessionId, String quizSetId) {
        findSession(sessionId);
        List<SessionQuiz> quizzes = sessionQuizRepository.findBySessionIdAndQuizSetIdOrderByCreatedAtAscQuestionOrderAsc(sessionId, quizSetId);
        if (quizzes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz set not found");
        }
        sessionQuizRepository.deleteQuizSet(sessionId, quizSetId);
    }

    private ChatSession findSession(Long sessionId) {
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
    }

    private SessionQuiz toEntity(
            ChatSession session,
            Long documentId,
            String quizSetId,
            String quizSetTitle,
            SessionQuizItemRequest question
    ) {
        return new SessionQuiz(
                session,
                documentId,
                quizSetId,
                quizSetTitle,
                question.getOrder(),
                defaultString(question.getType(), "short_answer"),
                defaultString(question.getQuestion(), ""),
                writeChoices(question),
                defaultString(question.getCorrectAnswer(), ""),
                defaultString(question.getModelAnswer(), ""),
                defaultString(question.getExplanation(), ""),
                defaultString(question.getSourceEvidence(), ""),
                defaultString(question.getDifficulty(), "medium")
        );
    }

    private String writeChoices(SessionQuizItemRequest question) {
        try {
            return objectMapper.writeValueAsString(question.getChoices() == null ? List.of() : question.getChoices());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to serialize choices");
        }
    }

    private String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
