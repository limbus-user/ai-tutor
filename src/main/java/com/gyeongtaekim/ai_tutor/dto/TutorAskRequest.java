package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class TutorAskRequest {
    private String question;
    private Long documentId;
    private List<Long> documentIds;
}