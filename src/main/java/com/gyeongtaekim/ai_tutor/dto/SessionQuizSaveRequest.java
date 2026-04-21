package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class SessionQuizSaveRequest {
    private Long documentId;
    private String quizSetTitle;
    private List<SessionQuizItemRequest> questions;
}
