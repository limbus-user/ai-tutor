package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class RagGeneratedQuestionsResponse {
    private Long documentId;
    private String title;
    private String storedFileName;
    private List<RagGeneratedQuestionResponse> questions;
}
