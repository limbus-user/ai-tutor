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
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        String title = request.getTitle() == null || request.getTitle().isBlank()
                ? "New Chat Session"
                : request.getTitle().trim();

        ChatSession.SessionType type = parseSessionType(request.getType());
        return new ChatSessionResponse(chatSessionRepository.save(new ChatSession(user, title, type)));
    }

    public List<ChatSessionResponse> getSessions(Long userId) {
        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ChatSessionResponse::new)
                .toList();
    }

    public ChatSessionResponse getSession(Long sessionId) {
        return new ChatSessionResponse(findSession(sessionId));
    }

    public List<ChatMessageResponse> getMessages(Long sessionId) {
        findSession(sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(ChatMessageResponse::new)
                .toList();
    }

    public ChatMessageResponse addMessage(Long sessionId, ChatMessageCreateRequest request) {
        ChatSession session = findSession(sessionId);
        ChatMessage.MessageRole role = ChatMessage.MessageRole.valueOf(request.getRole().toUpperCase(Locale.ROOT));
        ChatMessage message = new ChatMessage(session, role, request.getContent(), request.getSourceReferences());
        session.touch();
        chatSessionRepository.save(session);
        return new ChatMessageResponse(chatMessageRepository.save(message));
    }

    public ChatSessionResponse closeSession(Long sessionId) {
        ChatSession session = findSession(sessionId);
        session.close();
        return new ChatSessionResponse(chatSessionRepository.save(session));
    }

    public ChatSessionResponse updateSessionTitle(Long sessionId, ChatSessionTitleUpdateRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session title is required");
        }

        ChatSession session = findSession(sessionId);
        session.updateTitle(request.getTitle().trim());
        return new ChatSessionResponse(chatSessionRepository.save(session));
    }

    public List<RagDocumentSummaryResponse> getSessionDocuments(Long sessionId) {
        findSession(sessionId);
        List<Long> documentIds = chatSessionDocumentRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(ChatSessionDocument::getDocumentId)
                .toList();

        return documentIds.stream()
                .map(ragDocumentRepository::findById)
                .flatMap(java.util.Optional::stream)
                .map(RagDocumentSummaryResponse::new)
                .toList();
    }

    public RagDocumentSummaryResponse attachSessionDocument(Long sessionId, Long documentId) {
        ChatSession session = findSession(sessionId);
        var document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!chatSessionDocumentRepository.existsBySessionIdAndDocumentId(sessionId, documentId)) {
            chatSessionDocumentRepository.save(new ChatSessionDocument(session, documentId));
        }
        return new RagDocumentSummaryResponse(document);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        findSession(sessionId);
        chatMessageRepository.deleteAllBySessionId(sessionId);
        chatSessionDocumentRepository.deleteAllBySessionId(sessionId);
        sessionQuizRepository.deleteAllBySessionId(sessionId);
        chatSessionRepository.deleteById(sessionId);
    }

    private ChatSession findSession(Long sessionId) {
        return chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
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
