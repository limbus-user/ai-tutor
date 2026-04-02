package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class TutorAskResponse {
    private Long sessionId;
    private String question;
    private String answer;
    private List<String> sources;
}
