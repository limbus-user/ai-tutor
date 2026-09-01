package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.ChatMessage;
import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.ChatSessionDocument;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.ChatMessageCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatMessageResponse;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionCreateRequest;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionResponse;
import com.gyeongtaekim.ai_tutor.dto.ChatSessionTitleUpdateRequest;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.repository.ChatMessageRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.SessionQuizRepository;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionDocumentRepository chatSessionDocumentRepository;
    private final SessionQuizRepository sessionQuizRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final UserRepository userRepository;

    public ChatSessionResponse createSession(ChatSessionCreateRequest request) {
        return createSession(request, null);
    }

    public ChatSessionResponse createSession(ChatSessionCreateRequest request, User currentUser) {
        User user = currentUser != null
                ? currentUser
                : userRepository.findById(request.getUserId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String title = request.getTitle() == null || request.getTitle().isBlank()
                ? "New Chat Session"
                : request.getTitle().trim();

        ChatSession.SessionType type = parseSessionType(request.getType());
        return new ChatSessionResponse(chatSessionRepository.save(new ChatSession(user, title, type)));
    }

    public List<ChatSessionResponse> getSessions(Long userId) {
        return getSessions(userId, null);
    }

    public List<ChatSessionResponse> getSessions(Long userId, User currentUser) {
        if (currentUser != null && !currentUser.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User id does not match authenticated user");
        }
        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ChatSessionResponse::new)
                .toList();
    }

    public ChatSessionResponse getSession(Long sessionId) {
        return getSession(sessionId, null);
    }

    public ChatSessionResponse getSession(Long sessionId, User currentUser) {
        return new ChatSessionResponse(findSession(sessionId, currentUser));
    }

    public List<ChatMessageResponse> getMessages(Long sessionId) {
        return getMessages(sessionId, null);
    }

    public List<ChatMessageResponse> getMessages(Long sessionId, User currentUser) {
        findSession(sessionId, currentUser);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(ChatMessageResponse::new)
                .toList();
    }

    public ChatMessageResponse addMessage(Long sessionId, ChatMessageCreateRequest request) {
        return addMessage(sessionId, request, null);
    }

    public ChatMessageResponse addMessage(Long sessionId, ChatMessageCreateRequest request, User currentUser) {
        ChatSession session = findSession(sessionId, currentUser);
        ChatMessage.MessageRole role = ChatMessage.MessageRole.valueOf(request.getRole().toUpperCase(Locale.ROOT));
        ChatMessage message = new ChatMessage(session, role, request.getContent(), request.getSourceReferences());
        session.touch();
        chatSessionRepository.save(session);
        return new ChatMessageResponse(chatMessageRepository.save(message));
    }

    public ChatSessionResponse closeSession(Long sessionId) {
        return closeSession(sessionId, null);
    }

    public ChatSessionResponse closeSession(Long sessionId, User currentUser) {
        ChatSession session = findSession(sessionId, currentUser);
        session.close();
        return new ChatSessionResponse(chatSessionRepository.save(session));
    }

    public ChatSessionResponse updateSessionTitle(Long sessionId, ChatSessionTitleUpdateRequest request) {
        return updateSessionTitle(sessionId, request, null);
    }

    public ChatSessionResponse updateSessionTitle(Long sessionId, ChatSessionTitleUpdateRequest request, User currentUser) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session title is required");
        }

        ChatSession session = findSession(sessionId, currentUser);
        session.updateTitle(request.getTitle().trim());
        return new ChatSessionResponse(chatSessionRepository.save(session));
    }

    public List<RagDocumentSummaryResponse> getSessionDocuments(Long sessionId) {
        return getSessionDocuments(sessionId, null);
    }

    public List<RagDocumentSummaryResponse> getSessionDocuments(Long sessionId, User currentUser) {
        findSession(sessionId, currentUser);
        List<Long> documentIds = chatSessionDocumentRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ChatSessionDocument::getDocumentId)
                .toList();

        return documentIds.stream()
                .map(documentId -> currentUser == null
                        ? ragDocumentRepository.findById(documentId)
                        : ragDocumentRepository.findByIdAndUserId(documentId, currentUser.getId()))
                .flatMap(java.util.Optional::stream)
                .map(RagDocumentSummaryResponse::new)
                .toList();
    }

    public RagDocumentSummaryResponse attachSessionDocument(Long sessionId, Long documentId) {
        return attachSessionDocument(sessionId, documentId, null);
    }

    public RagDocumentSummaryResponse attachSessionDocument(Long sessionId, Long documentId, User currentUser) {
        ChatSession session = findSession(sessionId, currentUser);
        var document = currentUser == null
                ? ragDocumentRepository.findById(documentId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"))
                : ragDocumentRepository.findByIdAndUserId(documentId, currentUser.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!chatSessionDocumentRepository.existsBySessionIdAndDocumentId(sessionId, documentId)) {
            chatSessionDocumentRepository.save(new ChatSessionDocument(session, documentId));
        }
        return new RagDocumentSummaryResponse(document);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        deleteSession(sessionId, null);
    }

    @Transactional
    public void deleteSession(Long sessionId, User currentUser) {
        findSession(sessionId, currentUser);
        chatMessageRepository.deleteAllBySessionId(sessionId);
        chatSessionDocumentRepository.deleteAllBySessionId(sessionId);
        sessionQuizRepository.deleteAllBySessionId(sessionId);
        chatSessionRepository.deleteById(sessionId);
    }

    private ChatSession findSession(Long sessionId) {
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
    }

    private ChatSession findSession(Long sessionId, User currentUser) {
        ChatSession session = findSession(sessionId);
        if (currentUser != null && !currentUser.getId().equals(session.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chat session does not belong to authenticated user");
        }
        return session;
    }

    private ChatSession.SessionType parseSessionType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return ChatSession.SessionType.STUDY;
        }

        try {
            return ChatSession.SessionType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session type");
        }
    }
}
