package com.gyeongtaekim.ai_tutor.dto;

import com.gyeongtaekim.ai_tutor.domain.Concept;
import lombok.Getter;

@Getter
public class ConceptResponse {
    private final Long id;
    private final String subject;
    private final String unitName;
    private final String name;
    private final String description;

    public ConceptResponse(Concept concept) {
        this.id = concept.getId();
        this.subject = concept.getSubject();
        this.unitName = concept.getUnitName();
        this.name = concept.getName();
        this.description = concept.getDescription();
    }
}
