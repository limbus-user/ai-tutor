package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WeaknessAnalysisDto {
    private Long userId;
    private Map<String, Integer> weaknessTopics; // 단원별 오답 빈도
    private String summary; // 요약
}
