package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LearningSessionDto {
    private Long userId;
    private String selectedChapter;
    private List<String> generatedQuestions;
    private List<WrongAnswerDto> wrongAnswers;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
