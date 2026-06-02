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
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found"));
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        boolean correct = normalize(problem.getAnswer()).equals(normalize(request.getSubmittedAnswer()));
        String feedback = correct
                ? "정답입니다. " + problem.getExplanation()
                : "오답입니다. 정답은 '" + problem.getAnswer() + "' 입니다. " + problem.getExplanation();

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
