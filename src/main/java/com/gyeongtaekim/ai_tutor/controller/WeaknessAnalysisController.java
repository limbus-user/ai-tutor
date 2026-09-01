package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.WeaknessAnalysisDto;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.WeaknessAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class WeaknessAnalysisController {

    private final WeaknessAnalysisService weaknessAnalysisService;
    private final CurrentUserService currentUserService;

    @GetMapping("/{userId}")
    public ResponseEntity<WeaknessAnalysisDto> getWeaknessAnalysis(
            @PathVariable Long userId,
            Authentication authentication
    ) {
        try {
            User user = currentUserService.resolveUser(authentication, userId);
            WeaknessAnalysisDto analysis = weaknessAnalysisService.analyzeWeakness(user.getId());
            return ResponseEntity.ok(analysis);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
