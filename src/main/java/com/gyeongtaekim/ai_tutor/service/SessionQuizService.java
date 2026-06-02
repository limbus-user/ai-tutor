package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.SessionQuiz;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizItemRequest;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizResponse;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizSaveRequest;
import com.gyeongtaekim.ai_tutor.dto.SessionQuizSubmitRequest;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.SessionQuizRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
                : abbreviate(request.getQuizSetTitle().trim(), 255);

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

    @Transactional
    public SessionQuizResponse submitQuiz(Long sessionId, Long quizId, SessionQuizSubmitRequest request) {
        if (request.getSubmittedAnswer() == null || request.getSubmittedAnswer().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "submittedAnswer is required");
        }

        SessionQuiz quiz = sessionQuizRepository.findByIdAndSessionId(quizId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found"));

        String submittedAnswer = request.getSubmittedAnswer().trim();
        boolean correct = isCorrectQuizAnswer(quiz, submittedAnswer);
        quiz.submitResult(submittedAnswer, correct, buildEvaluationFeedback(quiz, submittedAnswer, correct));

        return new SessionQuizResponse(sessionQuizRepository.save(quiz));
    }

    @Transactional
    public SessionQuizResponse resetQuiz(Long sessionId, Long quizId) {
        SessionQuiz quiz = sessionQuizRepository.findByIdAndSessionId(quizId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz not found"));
        quiz.resetProgress();
        return new SessionQuizResponse(sessionQuizRepository.save(quiz));
    }

    @Transactional
    public List<SessionQuizResponse> resetQuizSet(Long sessionId, String quizSetId) {
        findSession(sessionId);
        List<SessionQuiz> quizzes = sessionQuizRepository.findBySessionIdAndQuizSetIdOrderByCreatedAtAscQuestionOrderAsc(sessionId, quizSetId);
        if (quizzes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz set not found");
        }
        quizzes.forEach(SessionQuiz::resetProgress);
        return sessionQuizRepository.saveAll(quizzes).stream()
                .map(SessionQuizResponse::new)
                .toList();
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
                defaultString(question.getDifficulty(), "medium"),
                defaultString(question.getConceptTag(), "핵심 개념"),
                defaultString(question.getUnderstandingLevel(), "CONCEPT_UNDERSTANDING")
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

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isCorrectQuizAnswer(SessionQuiz quiz, String submittedAnswer) {
        String expected = normalizeForGrading(quiz.getCorrectAnswer());
        String submitted = normalizeForGrading(submittedAnswer);

        if (expected.isBlank() || submitted.isBlank()) {
            return false;
        }

        String type = normalize(quiz.getType());

        // 객관식, OX는 기존처럼 정확히 일치해야 정답
        if ("multiple_choice".equals(type)
                || "multiple-choice".equals(type)
                || "true_false".equals(type)
                || "true-false".equals(type)
                || "ox".equals(type)) {
            return expected.equals(submitted);
        }

        // 주관식은 완전 일치, 포함 관계, 핵심어 유사도 기준으로 채점
        if (expected.equals(submitted)
                || expected.contains(submitted)
                || submitted.contains(expected)) {
            return true;
        }

        String modelAnswer = normalizeForGrading(quiz.getModelAnswer());
        if (!modelAnswer.isBlank() && submitted.contains(modelAnswer)) {
            return true;
        }

        return isSimilarSubjectiveAnswer(expected + " " + modelAnswer, submitted);
    }

    private boolean isSimilarSubjectiveAnswer(String expected, String submitted) {
        List<String> expectedKeywords = extractMeaningfulTokens(expected);
        List<String> submittedKeywords = extractMeaningfulTokens(submitted);

        if (expectedKeywords.isEmpty() || submittedKeywords.isEmpty()) {
            return false;
        }

        // 너무 짧은 단답은 주관식 정답으로 인정하지 않음
        // 예: "배열, 트리" 같은 키워드 나열 방지
        if (submittedKeywords.size() < 3) {
            return false;
        }

        long matchedCount = expectedKeywords.stream()
                .filter(expectedToken ->
                        submittedKeywords.stream().anyMatch(submittedToken ->
                                submittedToken.contains(expectedToken)
                                        || expectedToken.contains(submittedToken)
                        )
                )
                .count();

        double matchRatio = (double) matchedCount / expectedKeywords.size();

        return matchRatio >= 0.5;
    }

    private List<String> extractMeaningfulTokens(String text) {
        return Arrays.stream(text.split("[\\s,.;:()\\[\\]{}\"'“”‘’]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !isStopWord(token))
                .distinct()
                .toList();
    }

    private boolean isStopWord(String token) {
        return List.of(
                "그리고", "또는", "하지만", "그러나", "따라서",
                "이다", "한다", "있는", "없는", "것은", "것을", "것이",
                "이를", "이것", "저것", "해당", "대한", "위한",
                "수", "등", "및"
        ).contains(token);
    }

    private String normalizeForGrading(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private String buildEvaluationFeedback(SessionQuiz quiz, String submittedAnswer, boolean correct) {
        StringBuilder feedback = new StringBuilder();
        feedback.append(correct ? "정답입니다." : "오답입니다.").append("\n");
        feedback.append("내 답: ").append(submittedAnswer).append("\n");
        feedback.append("정답: ").append(quiz.getCorrectAnswer()).append("\n");

        if (quiz.getModelAnswer() != null && !quiz.getModelAnswer().isBlank()) {
            feedback.append("모범답안: ").append(quiz.getModelAnswer()).append("\n");
        }

        if (correct) {
            feedback.append("비교 피드백: 핵심 답안 요소가 정답과 일치합니다.");
        } else if (hasMeaningfulOverlap(submittedAnswer, quiz)) {
            feedback.append("비교 피드백: 일부 핵심 표현은 맞았지만 정답 기준과 완전히 일치하지 않습니다.");
        } else {
            feedback.append("비교 피드백: 정답의 핵심 개념이나 표현이 답안에 충분히 반영되지 않았습니다.");
        }

        if (quiz.getExplanation() != null && !quiz.getExplanation().isBlank()) {
            feedback.append("\n해설: ").append(quiz.getExplanation());
        }

        return feedback.toString();
    }

    private boolean hasMeaningfulOverlap(String submittedAnswer, SessionQuiz quiz) {
        String reference = (defaultString(quiz.getCorrectAnswer(), "") + " " + defaultString(quiz.getModelAnswer(), ""))
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(submittedAnswer.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(token -> token.length() >= 2)
                .anyMatch(reference::contains);
    }
}
