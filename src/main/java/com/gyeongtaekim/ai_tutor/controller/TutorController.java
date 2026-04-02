package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.TutorAskRequest;
import com.gyeongtaekim.ai_tutor.dto.TutorAskResponse;
import com.gyeongtaekim.ai_tutor.service.TutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tutor")
@RequiredArgsConstructor
public class TutorController {

    private final TutorService tutorService;

    @PostMapping("/sessions/{sessionId}/ask")
    public ResponseEntity<TutorAskResponse> ask(
            @PathVariable Long sessionId,
            @RequestBody TutorAskRequest request
    ) {
        return ResponseEntity.ok(tutorService.ask(sessionId, request));
    }
}
