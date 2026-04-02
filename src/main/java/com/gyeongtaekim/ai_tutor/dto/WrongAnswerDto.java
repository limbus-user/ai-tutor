package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WrongAnswerDto {
    private Long userId;
    private String question;
    private String userAnswer;
    private String correctAnswer;
    private LocalDateTime timestamp;
}
