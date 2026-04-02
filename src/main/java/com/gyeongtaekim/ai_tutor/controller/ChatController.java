package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.ChatMessageCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatMessageResponse;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionResponse;
import com.gyeongtaekim.ai_tutor.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionResponse> createSession(@RequestBody ChatSessionCreateRequest request) {
        return ResponseEntity.ok(chatService.createSession(request));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSessionResponse>> getSessions(@RequestParam Long userId) {
        return ResponseEntity.ok(chatService.getSessions(userId));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSessionResponse> getSession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(chatService.getSession(sessionId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(@PathVariable Long sessionId) {
        return ResponseEntity.ok(chatService.getMessages(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageResponse> addMessage(
            @PathVariable Long sessionId,
            @RequestBody ChatMessageCreateRequest request
    ) {
        return ResponseEntity.ok(chatService.addMessage(sessionId, request));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public ResponseEntity<ChatSessionResponse> closeSession(@PathVariable Long sessionId) {
        return ResponseEntity.ok(chatService.closeSession(sessionId));
    }
}
