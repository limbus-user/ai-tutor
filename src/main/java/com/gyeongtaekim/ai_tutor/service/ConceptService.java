package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.Concept;
import com.gyeongtaekim.ai_tutor.dto.ConceptRequest;
import com.gyeongtaekim.ai_tutor.dto.ConceptResponse;
import com.gyeongtaekim.ai_tutor.repository.ConceptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConceptService {

    private final ConceptRepository conceptRepository;

    public ConceptResponse createConcept(ConceptRequest request) {
        Concept concept = new Concept(
                request.getSubject(),
                request.getUnitName(),
                request.getName(),
                request.getDescription()
        );
        return new ConceptResponse(conceptRepository.save(concept));
    }

    public List<ConceptResponse> getAllConcepts() {
        return conceptRepository.findAll().stream()
                .map(ConceptResponse::new)
                .toList();
    }
}
