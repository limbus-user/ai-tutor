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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TutorService {

    private static final Pattern KOREAN_TOPIC_PARTICLE = Pattern.compile("(은|는|이|가|을|를|과|와|도|만)$");
    private static final Pattern QUESTION_WORDS = Pattern.compile("(뭐야|무엇|설명|차이|다른 점|비교)");

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final LearningMemoryRepository learningMemoryRepository;
    private final RagService ragService;
    private final OllamaService ollamaService;

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

        LearningMemory memory = learningMemoryRepository.findByUserId(session.getUser().getId()).orElse(null);
        List<ChatMessage> recentMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        String groundedQuestion = rewriteQuestionWithContext(question, recentMessages);
        RagQueryResponse ragResponse = ragService.query(groundedQuestion, request.getDocumentId());

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
            return "현재 업로드된 학습 자료에서 질문과 직접 관련된 근거를 찾지 못했습니다. 질문을 더 구체적으로 하거나 관련 PDF를 업로드해 주세요.";
        }

        String fallbackAnswer = buildFallbackAnswer(ragResponse);
        if (ollamaService.isEnabled()) {
            String ollamaAnswer = ollamaService.generate(
                    """
                    You are a grounded AI tutor.
                    Answer only from the provided evidence.
                    If the evidence is insufficient, say so clearly.
                    Answer in Korean.
                    """,
                    buildPrompt(question, ragResponse, memory, recentMessages)
            );
            if (ollamaAnswer != null && !ollamaAnswer.isBlank()) {
                return formatLlmAnswer(ollamaAnswer, ragResponse);
            }
        }

        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            return fallbackAnswer;
        }

        try {
            ChatLanguageModel model = OpenAiChatModel.builder()
                    .apiKey(openAiApiKey)
                    .modelName(openAiChatModelName)
                    .timeout(Duration.ofSeconds(30))
                    .build();

            String llmAnswer = model.generate(buildPrompt(question, ragResponse, memory, recentMessages));
            if (llmAnswer == null || llmAnswer.isBlank()) {
                return fallbackAnswer;
            }

            return formatLlmAnswer(llmAnswer, ragResponse);
        } catch (Exception e) {
            return fallbackAnswer;
        }
    }

    private String buildFallbackAnswer(RagQueryResponse ragResponse) {
        String conciseAnswer = ragResponse.getAnswer() == null ? "" : ragResponse.getAnswer().trim();
        conciseAnswer = trimToSentenceLimit(conciseAnswer, 8);
        return conciseAnswer + "\n\n출처:\n" + String.join("\n", ragResponse.getSources());
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
        prompt.append("Explain like a patient university tutor.\n");
        prompt.append("Use the provided evidence as the main basis, but make the explanation easy to understand.\n");
        prompt.append("When helpful, include definitions, step-by-step reasoning, comparisons, and simple examples that are consistent with the evidence.\n\n");

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
        prompt.append("3. If the question asks for explanation, answer in 5 to 8 sentences with clear structure.\n");
        prompt.append("4. Start with the core answer, then explain why using the evidence.\n");
        prompt.append("5. Include a simple example or comparison when it helps understanding, but do not invent facts outside the evidence.\n");
        prompt.append("6. If the evidence is limited, clearly say what is confirmed and what is not confirmed.\n");
        prompt.append("7. Do not include a source list or citation heading in the body.\n");
        return prompt.toString();
    }

    private String formatLlmAnswer(String llmAnswer, RagQueryResponse ragResponse) {
        String cleaned = sanitizeLlmAnswer(llmAnswer);
        return cleaned + "\n\n출처:\n" + String.join("\n", ragResponse.getSources());
    }

    private String sanitizeLlmAnswer(String llmAnswer) {
        String cleaned = llmAnswer == null ? "" : llmAnswer.trim();
        cleaned = cleaned.replaceAll("(?is)\\n*\\[Sources\\].*$", "");
        cleaned = cleaned.replaceAll("(?is)\\n*출처\\s*:\\s*.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*-\\s*.+\\[chunk\\s+\\d+\\]\\s*$", "");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();
        return trimToSentenceLimit(cleaned, 8);
    }

    private String trimToSentenceLimit(String text, int sentenceLimit) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String[] sentences = text.trim().split("(?<=[.!?])\\s+|(?<=다)\\s+|(?<=요)\\s+");
        if (sentences.length <= sentenceLimit) {
            return text.trim();
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sentenceLimit; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(sentences[i].trim());
        }
        return builder.toString().trim();
    }

    private String rewriteQuestionWithContext(String question, List<ChatMessage> recentMessages) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            return normalizedQuestion;
        }

        if (!needsContextRewrite(normalizedQuestion)) {
            return normalizedQuestion;
        }

        String previousUserQuestion = findPreviousUserQuestion(recentMessages);
        if (previousUserQuestion == null || previousUserQuestion.isBlank()) {
            return normalizedQuestion;
        }

        if (containsComparisonIntent(normalizedQuestion)) {
            String previousTopic = extractPrimaryTopic(previousUserQuestion);
            String currentTopic = extractPrimaryTopic(normalizedQuestion);
            if (!previousTopic.isBlank() && !currentTopic.isBlank() && !previousTopic.equals(currentTopic)) {
                return previousTopic + "과 " + currentTopic + "의 차이점이 뭐야?";
            }
            if (!previousTopic.isBlank()) {
                return previousTopic + "과 관련된 차이점을 설명해 줘.";
            }
        }

        if (normalizedQuestion.startsWith("그럼") || normalizedQuestion.startsWith("그건") || normalizedQuestion.startsWith("그게")) {
            return previousUserQuestion + " 그리고 " + normalizedQuestion;
        }

        return normalizedQuestion;
    }

    private boolean needsContextRewrite(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.startsWith("그럼")
                || normalized.startsWith("그건")
                || normalized.startsWith("그게")
                || normalized.contains("차이")
                || normalized.contains("다른 점")
                || normalized.contains("비교");
    }

    private boolean containsComparisonIntent(String question) {
        return question.contains("차이") || question.contains("다른 점") || question.contains("비교");
    }

    private String findPreviousUserQuestion(List<ChatMessage> recentMessages) {
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            ChatMessage message = recentMessages.get(i);
            if (message.getRole() == ChatMessage.MessageRole.USER && message.getContent() != null && !message.getContent().isBlank()) {
                String content = message.getContent().trim();
                if (!content.equals(recentMessages.get(recentMessages.size() - 1).getContent().trim())) {
                    return content;
                }
            }
        }
        return "";
    }

    private String extractPrimaryTopic(String question) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.isBlank()) {
            return "";
        }

        String[] tokens = normalized.split("\\s+");
        List<String> candidates = new ArrayList<>();
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]", "");
            cleaned = KOREAN_TOPIC_PARTICLE.matcher(cleaned).replaceFirst("");
            if (cleaned.length() >= 2 && !QUESTION_WORDS.matcher(cleaned).find()) {
                candidates.add(cleaned);
            }
        }

        if (candidates.isEmpty()) {
            return "";
        }

        if (normalized.contains("다형성")) {
            return "다형성";
        }
        if (normalized.contains("상속")) {
            return "상속";
        }
        if (normalized.contains("캡슐화")) {
            return "캡슐화";
        }
        if (normalized.contains("오버라이드")) {
            return "오버라이드";
        }
        if (normalized.contains("객체")) {
            return "객체";
        }

        return candidates.get(candidates.size() - 1);
    }
}
