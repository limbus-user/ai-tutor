package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.ChatMessage;
import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.LearningMemory;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.dto.TutorAskRequest;
import com.gyeongtaekim.ai_tutor.dto.TutorAskResponse;
import com.gyeongtaekim.ai_tutor.repository.ChatMessageRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.LearningMemoryRepository;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TutorService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final LearningMemoryRepository learningMemoryRepository;
    private final RagService ragService;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.chat.model:gpt-4o-mini}")
    private String openAiChatModelName;

    public TutorAskResponse ask(Long sessionId, TutorAskRequest request) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));

        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required");
        }

        chatMessageRepository.save(new ChatMessage(session, ChatMessage.MessageRole.USER, question, null));

        RagQueryResponse ragResponse = ragService.query(question);
        LearningMemory memory = learningMemoryRepository.findByUserId(session.getUser().getId()).orElse(null);
        List<ChatMessage> recentMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        String answer = buildGroundedAnswer(question, ragResponse, memory, recentMessages);
        String sourceReferences = ragResponse.getSources().isEmpty()
                ? null
                : String.join(" | ", ragResponse.getSources());

        chatMessageRepository.save(new ChatMessage(
                session,
                ChatMessage.MessageRole.ASSISTANT,
                answer,
                sourceReferences
        ));
        session.touch();
        chatSessionRepository.save(session);

        return new TutorAskResponse(sessionId, question, answer, ragResponse.getSources());
    }

    private String buildGroundedAnswer(
            String question,
            RagQueryResponse ragResponse,
            LearningMemory memory,
            List<ChatMessage> recentMessages
    ) {
        if (ragResponse.getSources().isEmpty()) {
            return "현재 저장된 학습 자료에서 이 질문에 대한 직접적인 근거를 찾지 못했습니다. "
                    + "질문을 더 구체적으로 하거나 관련 PDF를 먼저 업로드해 주세요.";
        }

        String fallbackAnswer = buildFallbackAnswer(question, ragResponse, memory);
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            return fallbackAnswer;
        }

        try {
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .apiKey(openAiApiKey)
                    .modelName(openAiChatModelName)
                    .timeout(Duration.ofSeconds(30))
                    .build();

            String prompt = buildPrompt(question, ragResponse, memory, recentMessages);
            String llmAnswer = model.generate(prompt);
            if (llmAnswer == null || llmAnswer.isBlank()) {
                return fallbackAnswer;
            }

            return llmAnswer.trim() + "\n\n[Sources]\n" + String.join("\n", ragResponse.getSources());
        } catch (Exception e) {
            return fallbackAnswer + "\n\n[LLM Fallback]\nOpenAI call failed: " + e.getMessage();
        }
    }

    private String buildFallbackAnswer(String question, RagQueryResponse ragResponse, LearningMemory memory) {
        StringBuilder answer = new StringBuilder();
        answer.append("학습 자료 근거를 바탕으로 답변합니다.\n\n");
        answer.append(ragResponse.getAnswer());

        if (memory != null && !memory.getPreferences().isBlank()) {
            answer.append("\n\n[학습 메모 반영]\n");
            answer.append("설명 선호: ").append(memory.getPreferences());
        }

        if (memory != null && !memory.getWeakConceptSummary().isBlank()) {
            answer.append("\n");
            answer.append("주의할 취약 개념: ").append(memory.getWeakConceptSummary());
        }

        answer.append("\n\n[질문]\n").append(question);
        answer.append("\n\n[Sources]\n").append(String.join("\n", ragResponse.getSources()));
        return answer.toString();
    }

    private String buildPrompt(
            String question,
            RagQueryResponse ragResponse,
            LearningMemory memory,
            List<ChatMessage> recentMessages
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a grounded AI tutor.\n");
        prompt.append("Answer only from the provided evidence.\n");
        prompt.append("If the evidence is insufficient, say so clearly.\n");
        prompt.append("Keep the explanation accurate, educational, and concise.\n\n");

        if (memory != null) {
            prompt.append("[Learning Memory]\n");
            prompt.append("Weak concepts: ").append(memory.getWeakConceptSummary()).append("\n");
            prompt.append("History summary: ").append(memory.getHistorySummary()).append("\n");
            prompt.append("Preferences: ").append(memory.getPreferences()).append("\n\n");
        }

        prompt.append("[Recent Conversation]\n");
        recentMessages.stream()
                .skip(Math.max(0, recentMessages.size() - 6))
                .forEach(message -> prompt.append(message.getRole().name()).append(": ").append(message.getContent()).append("\n"));

        prompt.append("\n[Evidence]\n");
        prompt.append(ragResponse.getAnswer()).append("\n\n");

        prompt.append("[Question]\n");
        prompt.append(question).append("\n\n");

        prompt.append("[Instructions]\n");
        prompt.append("1. Answer in Korean.\n");
        prompt.append("2. Use only the evidence above.\n");
        prompt.append("3. Explain in a tutoring style.\n");
        prompt.append("4. End with a short bullet list of cited sources.\n");
        return prompt.toString();
    }
}
