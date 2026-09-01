package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.Concept;
import com.gyeongtaekim.ai_tutor.domain.Problem;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.domain.UserProblemAttempt;
import com.gyeongtaekim.ai_tutor.dto.ProblemCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ProblemResponse;
import com.gyeongtaekim.ai_tutor.dto.ProblemSubmissionRequest;
import com.gyeongtaekim.ai_tutor.dto.ProblemSubmissionResponse;
import com.gyeongtaekim.ai_tutor.dto.ProblemViewResponse;
import com.gyeongtaekim.ai_tutor.repository.ConceptRepository;
import com.gyeongtaekim.ai_tutor.repository.ProblemRepository;
import com.gyeongtaekim.ai_tutor.repository.UserProblemAttemptRepository;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final ConceptRepository conceptRepository;
    private final UserRepository userRepository;
    private final UserProblemAttemptRepository userProblemAttemptRepository;
    private final ReviewService reviewService;

    public ProblemResponse createProblem(ProblemCreateRequest request) {
        List<Long> conceptIds = request.getConceptIds() == null ? Collections.emptyList() : request.getConceptIds();
        List<Concept> concepts = conceptRepository.findAllById(conceptIds);
        if (concepts.size() != conceptIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Some concept IDs were not found");
        }

        Problem problem = new Problem(
                request.getQuestionText(),
                request.getAnswer(),
                request.getExplanation(),
                Problem.Difficulty.valueOf(request.getDifficulty().toUpperCase(Locale.ROOT)),
                parseUnderstandingLevel(request.getUnderstandingLevel()),
                parseProblemType(request.getType()),
                concepts
        );
        return new ProblemResponse(problemRepository.save(problem));
    }

    public ProblemViewResponse getProblem(Long problemId) {
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        return new ProblemViewResponse(problem);
    }

    public ProblemSubmissionResponse submitAnswer(Long problemId, ProblemSubmissionRequest request) {
        return submitAnswer(problemId, request, null);
    }

    public ProblemSubmissionResponse submitAnswer(Long problemId, ProblemSubmissionRequest request, User currentUser) {
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        User user = currentUser != null
                ? currentUser
                : userRepository.findById(request.getUserId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        boolean correct = isCorrectAnswer(problem, request.getSubmittedAnswer());

        System.out.println("[submitAnswer 호출됨]");
        System.out.println("[문제 ID] " + problemId);
        System.out.println("[문제 타입] " + problem.getType());
        System.out.println("[모범답안] " + problem.getAnswer());
        System.out.println("[제출답안] " + request.getSubmittedAnswer());
        System.out.println("[최종 채점결과] " + correct);


        String feedback = correct
                ? "정답입니다. 핵심 의미가 모범답안과 일치합니다. " + problem.getExplanation()
                : "오답입니다. 모범답안의 핵심 내용은 '" + problem.getAnswer() + "' 입니다. " + problem.getExplanation();

        UserProblemAttempt attempt = new UserProblemAttempt(
                user,
                problem,
                request.getSubmittedAnswer(),
                correct,
                feedback
        );

        UserProblemAttempt savedAttempt = userProblemAttemptRepository.save(attempt);
        if (!correct) {
            reviewService.recordWrongAnswer(user, problem, savedAttempt);
        }

        return new ProblemSubmissionResponse(savedAttempt);
    }

    private boolean isCorrectAnswer(Problem problem, String submittedAnswer) {
        String expected = normalizeForGrading(problem.getAnswer());
        String submitted = normalizeForGrading(submittedAnswer);

        if (expected.isBlank() || submitted.isBlank()) {
            return false;
        }

        // 객관식, OX는 정확히 일치해야 정답
        if (problem.getType() == Problem.ProblemType.MULTIPLE_CHOICE
                || problem.getType() == Problem.ProblemType.TRUE_FALSE) {
            return expected.equals(submitted);
        }

        // 주관식: 완전 일치 또는 서로 포함하면 정답
        if (expected.equals(submitted)
                || expected.contains(submitted)
                || submitted.contains(expected)) {
            return true;
        }



        boolean similar = isSimilarSubjectiveAnswer(expected, submitted);

        System.out.println("[채점 타입] " + problem.getType());
        System.out.println("[모범답안] " + problem.getAnswer());
        System.out.println("[제출답안] " + submittedAnswer);
        System.out.println("[채점결과] " + similar);

        return similar;
    }

    private boolean isSimilarSubjectiveAnswer(String expected, String submitted) {
        List<String> expectedKeywords = extractMeaningfulTokens(expected);
        List<String> submittedKeywords = extractMeaningfulTokens(submitted);

        if (expectedKeywords.isEmpty() || submittedKeywords.isEmpty()) {
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

        return matchRatio >= 0.35;
    }

    private List<String> extractMeaningfulTokens(String text) {
        return java.util.Arrays.stream(text.split("[\\s,.;:()\\[\\]{}\"'“”‘’]+"))
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Problem.ProblemType parseProblemType(String type) {
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem type is required");
        }

        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if ("OX".equals(normalized)) {
            return Problem.ProblemType.TRUE_FALSE;
        }
        return Problem.ProblemType.valueOf(normalized);
    }

    private Problem.UnderstandingLevel parseUnderstandingLevel(String understandingLevel) {
        if (understandingLevel == null || understandingLevel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "understandingLevel is required");
        }

        return Problem.UnderstandingLevel.valueOf(understandingLevel.trim().toUpperCase(Locale.ROOT));
    }
}
