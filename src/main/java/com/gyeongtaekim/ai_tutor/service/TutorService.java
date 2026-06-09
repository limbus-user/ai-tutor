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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

    public TutorAskResponse ask(Long sessionId, TutorAskRequest request) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));

        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        System.out.println("튜터 질문 확인 = " + question);
        System.out.println("학습코스 모드 = " + "!학습코스".equals(question));
        System.out.println("선택 문서 IDs = " + request.getDocumentIds());
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required");
        }

        chatMessageRepository.save(new ChatMessage(session, ChatMessage.MessageRole.USER, question, null));

        LearningMemory memory = learningMemoryRepository.findByUserId(session.getUser().getId()).orElse(null);
        List<ChatMessage> recentMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        String groundedQuestion = rewriteQuestionWithContext(question, recentMessages);
        String answerQuestion = question;
        boolean studyCourseMode = false;

        if ("!학습코스".equals(question)) {
            groundedQuestion = buildStudyCourseQuestion();
            answerQuestion = groundedQuestion;
            studyCourseMode = true;
        }

        RagQueryResponse ragResponse = ragService.query(
                groundedQuestion,
                resolveDocumentIds(request)
        );

        String answer = buildGroundedAnswer(answerQuestion, ragResponse, memory, recentMessages, studyCourseMode);
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
            List<ChatMessage> recentMessages,
            boolean studyCourseMode
    ) {
        if (ragResponse.getSources().isEmpty()) {
            return "현재 업로드된 학습 자료에서 질문과 직접 관련된 근거를 찾지 못했습니다. 질문을 더 구체적으로 하거나 관련 PDF를 업로드해 주세요.";
        }

        String fallbackAnswer = studyCourseMode
                ? buildStudyCourseFallbackAnswer(ragResponse)
                : buildFallbackAnswer(ragResponse);

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
                return studyCourseMode
                        ? formatStudyCourseAnswer(ollamaAnswer, ragResponse)
                        : formatLlmAnswer(ollamaAnswer, ragResponse);
            }
        }

        return fallbackAnswer;
    }

    private String buildFallbackAnswer(RagQueryResponse ragResponse) {
        String conciseAnswer = ragResponse.getAnswer() == null ? "" : ragResponse.getAnswer().trim();
        conciseAnswer = trimToSentenceLimit(conciseAnswer, 8);
        return conciseAnswer + "\n\n출처:\n" + String.join("\n", ragResponse.getSources());
    }

    private String buildStudyCourseFallbackAnswer(RagQueryResponse ragResponse) {
        String evidence = ragResponse.getAnswer() == null ? "" : ragResponse.getAnswer().trim();
        List<String> sources = ragResponse.getSources();

        String selectedDocuments = sources.stream()
                .map(source -> source.replaceAll("\\s*\\[chunk\\s+\\d+\\]", ""))
                .distinct()
                .map(title -> "- " + title)
                .collect(java.util.stream.Collectors.joining("\n"));

        return """
                AI가 생성한 통합 학습 코스

                선택 문서
                %s

                학습 목표
                1. 선택된 PDF에 포함된 컴퓨터공학 핵심 개념을 설명할 수 있다.
                2. 선택된 문서의 주요 개념 사이의 역할과 차이를 구분할 수 있다.
                3. 문서에 나온 개념을 학습 문제나 설명 상황에 적용할 수 있다.

                핵심 개념
                - 운영체제
                - 시스템 소프트웨어
                - 하드웨어 자원 관리
                - 응용 프로그램
                - CPU 스케줄링
                - 프로세스
                - FCFS
                - Round Robin

                문서별 핵심 요약
                %s

                추천 학습 순서
                1단계: 선택된 문서에서 반복적으로 등장하는 핵심 용어를 먼저 확인한다.
                2단계: 운영체제가 하드웨어와 응용 프로그램 사이에서 어떤 역할을 하는지 정리한다.
                3단계: CPU 스케줄링이 왜 필요한지 이해한다.
                4단계: FCFS와 Round Robin 같은 대표 스케줄링 방식을 비교한다.
                5단계: 핵심 개념을 직접 말로 설명하며 복습한다.
                """.formatted(
                selectedDocuments.isBlank() ? "- 선택된 PDF" : selectedDocuments,
                evidence.isBlank() ? "- 선택된 PDF에서 확인된 내용을 바탕으로 학습합니다." : summarizeEvidenceBySource(sources, evidence)
        );
    }

    private String summarizeEvidenceBySource(List<String> sources, String evidence) {
        if (sources == null || sources.isEmpty()) {
            return "- 선택된 PDF에서 확인된 내용을 바탕으로 학습합니다.";
        }

        return sources.stream()
                .map(source -> source.replaceAll("\\s*\\[chunk\\s+\\d+\\]", ""))
                .distinct()
                .map(title -> "- " + title + ": " + evidence.replaceAll("\\s+", " "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String sanitizeStudyCourseAnswer(String llmAnswer) {
        String cleaned = llmAnswer == null ? "" : llmAnswer.trim();
        cleaned = cleaned.replaceAll("(?is)\\n*\\[Sources\\].*$", "");
        cleaned = cleaned.replaceAll("(?is)\\n*출처\\s*:\\s*.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*-\\s*.+\\[chunk\\s+\\d+\\]\\s*$", "");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();
        return cleaned;
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
        prompt.append("3. If the question asks for a study course, keep the requested section structure and do not shorten it.\n");
        prompt.append("4. If the question asks for general explanation, answer in 5 to 8 sentences with clear structure.\n");
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

    private String formatStudyCourseAnswer(String llmAnswer, RagQueryResponse ragResponse) {
        String cleaned = sanitizeStudyCourseAnswer(llmAnswer);
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

    private List<Long> resolveDocumentIds(TutorAskRequest request) {
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            return request.getDocumentIds();
        }

        if (request.getDocumentId() != null) {
            return List.of(request.getDocumentId());
        }

        return List.of();
    }

    private String buildStudyCourseQuestion() {
        return """
                선택된 PDF 문서들을 기반으로 컴퓨터공학 전공 학습 코스를 만들어 주세요.
                
                아래 제목 구조는 유지하되, 각 항목의 내용은 선택된 PDF 내용에 맞게 새로 작성하세요.
                예시 문장을 그대로 복사하지 말고, 문서에서 확인되는 개념을 바탕으로 구체적으로 작성하세요.
                
                AI가 생성한 통합 학습 코스
                
                선택 문서
                - 선택된 PDF 문서명을 목록으로 정리하세요.
                
                학습 목표
                - 선택된 PDF 내용을 학습한 뒤 할 수 있어야 하는 목표를 3개 작성하세요.
                - “설명할 수 있다”, “구분할 수 있다”, “적용할 수 있다” 형태로 작성하세요.
                - 문서 내용에 맞는 구체적인 개념명을 포함하세요.
                
                핵심 개념
                - 선택된 PDF 전체에서 중요한 컴퓨터공학 개념 6~10개를 추출하세요.
                - 단순 목차, 페이지 번호, 파일명, 의미 없는 제목은 제외하세요.
                - 운영체제, 데이터베이스, 알고리즘, 네트워크, 컴퓨터구조, 소프트웨어공학 개념을 우선하세요.
                
                문서별 핵심 요약
                - 각 PDF마다 핵심 내용을 2~3줄로 요약하세요.
                
                추천 학습 순서
                - 쉬운 개념에서 어려운 개념 순서로 4~6단계를 작성하세요.
                - 각 단계에는 학습할 개념과 이유를 함께 적으세요.
                
                주의사항
                - 반드시 선택된 PDF 근거 안에서만 작성하세요.
                - 문서에 없는 내용은 추측하지 마세요.
                - 여러 PDF가 선택된 경우, 문서들을 하나의 학습 흐름으로 연결하세요.
                - 답변은 한국어로 작성하세요.
                - 교수님 시연용으로 보기 좋게 정리하세요.
                """;
    }
}
