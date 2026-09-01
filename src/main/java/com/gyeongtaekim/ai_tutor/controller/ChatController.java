package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.ChatMessageCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatMessageResponse;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionResponse;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionTitleUpdateRequest;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.service.ChatService;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final CurrentUserService currentUserService;

    @PostMapping("/sessions")
    public ResponseEntity<ChatSessionResponse> createSession(
            @RequestBody ChatSessionCreateRequest request,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, request.getUserId());
        return ResponseEntity.ok(chatService.createSession(request, user));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSessionResponse>> getSessions(
            @RequestParam Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.getSessions(userId, user));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSessionResponse> getSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.getSession(sessionId, user));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageResponse>> getMessages(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.getMessages(sessionId, user));
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageResponse> addMessage(
            @PathVariable Long sessionId,
            @RequestBody ChatMessageCreateRequest request,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.addMessage(sessionId, request, user));
    }

    @PostMapping("/sessions/{sessionId}/close")
    public ResponseEntity<ChatSessionResponse> closeSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.closeSession(sessionId, user));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSessionResponse> updateSessionTitle(
            @PathVariable Long sessionId,
            @RequestBody ChatSessionTitleUpdateRequest request,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.updateSessionTitle(sessionId, request, user));
    }

    @GetMapping("/sessions/{sessionId}/documents")
    public ResponseEntity<List<RagDocumentSummaryResponse>> getSessionDocuments(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.getSessionDocuments(sessionId, user));
    }

    @PostMapping("/sessions/{sessionId}/documents/{documentId}")
    public ResponseEntity<RagDocumentSummaryResponse> attachSessionDocument(
            @PathVariable Long sessionId,
            @PathVariable Long documentId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(chatService.attachSessionDocument(sessionId, documentId, user));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        chatService.deleteSession(sessionId, user);
        return ResponseEntity.noContent().build();
    }
}
