package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.WeaknessAnalysisDto;
import com.gyeongtaekim.ai_tutor.service.WeaknessAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class WeaknessAnalysisController {

    private final WeaknessAnalysisService weaknessAnalysisService;

    @GetMapping("/{userId}")
    public ResponseEntity<WeaknessAnalysisDto> getWeaknessAnalysis(@PathVariable Long userId) {
        try {
            WeaknessAnalysisDto analysis = weaknessAnalysisService.analyzeWeakness(userId);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
