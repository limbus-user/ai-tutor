package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.ConceptRequest;
import com.gyeongtaekim.ai_tutor.dto.ConceptResponse;
import com.gyeongtaekim.ai_tutor.service.ConceptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/concepts")
@RequiredArgsConstructor
public class ConceptController {

    private final ConceptService conceptService;

    @PostMapping
    public ResponseEntity<ConceptResponse> createConcept(@RequestBody ConceptRequest request) {
        return ResponseEntity.ok(conceptService.createConcept(request));
    }

    @GetMapping
    public ResponseEntity<List<ConceptResponse>> getAllConcepts() {
        return ResponseEntity.ok(conceptService.getAllConcepts());
    }
}
