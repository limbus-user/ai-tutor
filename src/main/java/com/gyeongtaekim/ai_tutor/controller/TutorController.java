package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.TutorAskRequest;
import com.gyeongtaekim.ai_tutor.dto.TutorAskResponse;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.TutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tutor")
@RequiredArgsConstructor
public class TutorController {

    private final TutorService tutorService;
    private final CurrentUserService currentUserService;

    @PostMapping("/sessions/{sessionId}/ask")
    public ResponseEntity<TutorAskResponse> ask(
            @PathVariable Long sessionId,
            @RequestBody TutorAskRequest request,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(tutorService.ask(sessionId, request, user));
    }
}
