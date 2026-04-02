package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.LearningMemory;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.LearningMemoryRequest;
import com.gyeongtaekim.ai_tutor.dto.LearningMemoryResponse;
import com.gyeongtaekim.ai_tutor.repository.LearningMemoryRepository;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LearningMemoryService {

    private final LearningMemoryRepository learningMemoryRepository;
    private final UserRepository userRepository;

    public LearningMemoryResponse getOrCreateMemory(Long userId) {
        return new LearningMemoryResponse(findOrCreate(userId));
    }

    public LearningMemoryResponse updateMemory(Long userId, LearningMemoryRequest request) {
        LearningMemory memory = findOrCreate(userId);
        memory.update(
                defaultValue(request.getWeakConceptSummary()),
                defaultValue(request.getHistorySummary()),
                defaultValue(request.getPreferences())
        );
        return new LearningMemoryResponse(learningMemoryRepository.save(memory));
    }

    private LearningMemory findOrCreate(Long userId) {
        return learningMemoryRepository.findByUserId(userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
                    LearningMemory memory = new LearningMemory(user, "", "", "");
                    return learningMemoryRepository.save(memory);
                });
    }

    private String defaultValue(String value) {
        return value == null ? "" : value.trim();
    }
}
