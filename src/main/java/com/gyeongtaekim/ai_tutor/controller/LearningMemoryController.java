package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.LearningMemoryRequest;
import com.gyeongtaekim.ai_tutor.dto.LearningMemoryResponse;
import com.gyeongtaekim.ai_tutor.service.LearningMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class LearningMemoryController {

    private final LearningMemoryService learningMemoryService;

    @GetMapping("/{userId}")
    public ResponseEntity<LearningMemoryResponse> getMemory(@PathVariable Long userId) {
        return ResponseEntity.ok(learningMemoryService.getOrCreateMemory(userId));
    }

    @PutMapping("/{userId}")
    public ResponseEntity<LearningMemoryResponse> updateMemory(
            @PathVariable Long userId,
            @RequestBody LearningMemoryRequest request
    ) {
        return ResponseEntity.ok(learningMemoryService.updateMemory(userId, request));
    }
}
