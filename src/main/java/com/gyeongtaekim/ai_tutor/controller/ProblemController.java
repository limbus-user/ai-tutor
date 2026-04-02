package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.ProblemCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ProblemResponse;
import com.gyeongtaekim.ai_tutor.dto.ProblemSubmissionRequest;
import com.gyeongtaekim.ai_tutor.dto.ProblemSubmissionResponse;
import com.gyeongtaekim.ai_tutor.service.ProblemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;

    @PostMapping
    public ResponseEntity<ProblemResponse> createProblem(@RequestBody ProblemCreateRequest request) {
        return ResponseEntity.ok(problemService.createProblem(request));
    }

    @GetMapping("/{problemId}")
    public ResponseEntity<ProblemResponse> getProblem(@PathVariable Long problemId) {
        return ResponseEntity.ok(problemService.getProblem(problemId));
    }

    @PostMapping("/{problemId}/submit")
    public ResponseEntity<ProblemSubmissionResponse> submitAnswer(
            @PathVariable Long problemId,
            @RequestBody ProblemSubmissionRequest request
    ) {
        return ResponseEntity.ok(problemService.submitAnswer(problemId, request));
    }
}
