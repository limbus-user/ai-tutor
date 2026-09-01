package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.ChatMessage;
import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.ChatSessionDocument;
import com.gyeongtaekim.ai_tutor.domain.LearningMemory;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.dto.TutorAskRequest;
import com.gyeongtaekim.ai_tutor.dto.TutorAskResponse;
import com.gyeongtaekim.ai_tutor.repository.ChatMessageRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.LearningMemoryRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TutorService {

    private static final Pattern KOREAN_TOPIC_PARTICLE = Pattern.compile("(은|는|이|가|을|를|과|와|도|만)$");
    private static final Pattern QUESTION_WORDS = Pattern.compile("(뭐야|무엇|설명|차이|다른 점|비교)");

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionDocumentRepository chatSessionDocumentRepository;
    private final LearningMemoryRepository learningMemoryRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagService ragService;
    private final OllamaService ollamaService;

    public TutorAskResponse ask(Long sessionId, TutorAskRequest request) {
        return ask(sessionId, request, null);
    }

    public TutorAskResponse ask(Long sessionId, TutorAskRequest request, User currentUser) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
        if (currentUser != null && !currentUser.getId().equals(session.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chat session does not belong to authenticated user");
        }

        String question = request.getQuestion() == null ? "" : request.getQuestion().trim();
        System.out.println("튜터 질문 확인 = " + question);
        System.out.println("학습코스 모드 = " + "!학습코스".equals(question));
        System.out.println("선택 문서 IDs = " + request.getDocumentIds());
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required");
        }

        chatMessageRepository.save(new ChatMessage(session, ChatMessage.MessageRole.USER, question, null));

        if ("!도움말".equals(question)) {
            String answer = buildHelpAnswer();
            chatMessageRepository.save(new ChatMessage(
                    session,
                    ChatMessage.MessageRole.ASSISTANT,
                    answer,
                    null
            ));
            session.touch();
            chatSessionRepository.save(session);
            return new TutorAskResponse(sessionId, question, answer, List.of());
        }

        LearningMemory memory = learningMemoryRepository.findByUserId(session.getUser().getId()).orElse(null);
        List<ChatMessage> recentMessages = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Long> resolvedDocumentIds = resolveDocumentIds(session, request);
        List<RagDocument> selectedDocuments = findSelectedDocuments(currentUser, resolvedDocumentIds);
        String groundedQuestion = rewriteQuestionWithContext(question, recentMessages);
        String answerQuestion = question;
        boolean studyCourseMode = false;

        if ("!학습코스".equals(question)) {
            groundedQuestion = buildStudyCourseRetrievalQuery(selectedDocuments);
            answerQuestion = buildStudyCourseQuestion(selectedDocuments);
            answerQuestion = groundedQuestion;
            studyCourseMode = true;
        }

        RagQueryResponse ragResponse = ragService.query(
                currentUser,
                groundedQuestion,
                resolvedDocumentIds
        );

        String answer = buildGroundedAnswer(answerQuestion, ragResponse, memory, recentMessages, studyCourseMode, selectedDocuments);
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

    private String buildHelpAnswer() {
        return """
                사용 방법 안내

                1. PDF 파일을 업로드합니다.
                2. 왼쪽 PDF 목록에서 공부할 PDF 1개를 선택합니다.
                3. 처음이라면 채팅창에 !학습코스 를 입력하세요.
                4. 이후에는 궁금한 개념을 자연어로 질문하면 됩니다.

                예시 질문
                - 이 PDF의 핵심 개념을 쉽게 설명해줘
                - 이 단원에서 중요한 용어를 정리해줘
                - 헷갈리기 쉬운 개념을 비교해줘
                - 시험에 나올 만한 포인트를 알려줘

                명령어
                - !학습코스: 선택한 PDF 1개를 기준으로 학습 순서와 복습 계획을 만듭니다.
                - !도움말: 사용 방법을 다시 보여줍니다.

                주의
                - 학습코스는 PDF 1개만 선택한 상태에서 사용하는 것을 권장합니다.
                - 여러 PDF를 선택하면 일반 질문 답변에는 활용할 수 있지만, 처음 학습코스는 한 문서 기준이 더 정확합니다.
                """;
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
                    buildPrompt(question, ragResponse, memory, recentMessages, studyCourseMode)
            );
            if (ollamaAnswer != null && !ollamaAnswer.isBlank()) {
                return studyCourseMode
                        ? formatStudyCourseAnswer(ollamaAnswer, ragResponse)
                        : formatLlmAnswer(ollamaAnswer, ragResponse);
            }
        }

        return fallbackAnswer;
    }

    private String buildGroundedAnswer(
            String question,
            RagQueryResponse ragResponse,
            LearningMemory memory,
            List<ChatMessage> recentMessages,
            boolean studyCourseMode,
            List<RagDocument> selectedDocuments
    ) {
        return buildGroundedAnswer(question, ragResponse, memory, recentMessages, studyCourseMode);
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
                .map(this::stripChunkSourceDetails)
                .distinct()
                .map(title -> "- " + title)
                .collect(java.util.stream.Collectors.joining("\n"));
        List<String> concepts = extractStudyCourseConcepts(evidence);
        String conceptBlock = concepts.isEmpty()
                ? "- 선택된 PDF에서 확인된 핵심 개념"
                : concepts.stream()
                        .limit(10)
                        .map(concept -> "- " + concept)
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
                %s

                문서별 핵심 요약
                %s

                추천 학습 순서
                %s
                """.formatted(
                selectedDocuments.isBlank() ? "- 선택된 PDF" : selectedDocuments,
                conceptBlock,
                evidence.isBlank() ? "- 선택된 PDF에서 확인된 내용을 바탕으로 학습합니다." : summarizeEvidenceBySource(sources, evidence),
                buildStudyCourseSteps(concepts)
        );
    }

    private List<String> extractStudyCourseConcepts(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        for (String rawLine : evidence.split("\\R|(?<=[.!?])\\s+")) {
            String cleaned = cleanStudyCourseConcept(rawLine);
            if (cleaned.isBlank()) {
                continue;
            }

            int colonIndex = cleaned.indexOf(':');
            if (colonIndex > 1 && colonIndex <= 50) {
                String concept = cleanStudyCourseConcept(cleaned.substring(0, colonIndex));
                if (isUsefulStudyCourseConcept(concept)) {
                    concepts.add(concept);
                }
                continue;
            }

            if (cleaned.length() > 90) {
                cleaned = cleaned.substring(0, 90).trim();
            }
            if (isUsefulStudyCourseConcept(cleaned)) {
                concepts.add(cleaned);
            }
        }

        return concepts.stream().limit(10).toList();
    }

    private String buildStudyCourseSteps(List<String> concepts) {
        List<String> selectedConcepts = concepts == null || concepts.isEmpty()
                ? List.of("핵심 개념")
                : concepts.stream().limit(5).toList();

        List<String> steps = new ArrayList<>();
        steps.add("1단계: 선택한 PDF의 제목과 요약을 보고 전체 주제를 파악합니다.");
        steps.add("2단계: " + selectedConcepts.get(0) + "의 정의와 역할을 문서 근거로 정리합니다.");
        if (selectedConcepts.size() >= 2) {
            steps.add("3단계: " + selectedConcepts.get(0) + "와 " + selectedConcepts.get(1) + "의 관계를 비교합니다.");
        } else {
            steps.add("3단계: 핵심 개념이 어떤 상황에서 쓰이는지 예시와 함께 정리합니다.");
        }
        if (selectedConcepts.size() >= 3) {
            steps.add("4단계: " + selectedConcepts.get(2) + "까지 연결해 전체 흐름을 설명합니다.");
        } else {
            steps.add("4단계: 문서의 문장을 자기 말로 다시 설명하며 이해도를 확인합니다.");
        }
        steps.add("5단계: 선택한 PDF 근거만 사용해 예상 질문과 답안을 만들어 복습합니다.");

        return String.join("\n", steps);
    }

    private boolean isUsefulStudyCourseConcept(String concept) {
        if (concept == null || concept.isBlank()) {
            return false;
        }
        String normalized = concept.trim();
        if (normalized.length() < 2) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return !Set.of(
                "pdf",
                "text",
                "source",
                "chunk",
                "selected pdf",
                "선택된 pdf",
                "선택된 pdf에서 확인된 핵심 개념"
        ).contains(lower)
                && !lower.matches("^[0-9.()\\s-]+$");
    }

    private String cleanStudyCourseConcept(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("\\s*\\[chunk\\s+\\d+[^\\]]*\\]", "")
                .replaceAll("^[\\s\\-*:0-9.)]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripChunkSourceDetails(String source) {
        if (source == null) {
            return "";
        }
        return source.replaceAll("\\s*\\[chunk\\s+\\d+[^\\]]*\\]", "").trim();
    }

    private String summarizeEvidenceBySource(List<String> sources, String evidence) {
        if (sources == null || sources.isEmpty()) {
            return "- 선택된 PDF에서 확인된 내용을 바탕으로 학습합니다.";
        }

        return sources.stream()
                .map(this::stripChunkSourceDetails)
                .distinct()
                .map(title -> "- " + title + ": " + evidence.replaceAll("\\s+", " "))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String sanitizeStudyCourseAnswer(String llmAnswer) {
        String cleaned = llmAnswer == null ? "" : llmAnswer.trim();
        cleaned = cleaned.replaceAll("(?is)\\n*\\[Sources\\].*$", "");
        cleaned = cleaned.replaceAll("(?is)\\n*출처\\s*:\\s*.*$", "");
        cleaned = cleaned.replaceAll("(?im)^\\s*-\\s*.+\\[chunk\\s+\\d+\\]\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*#{1,6}\\s*", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*[-*]\\s+(?=(AI가 생성한 통합 학습 코스|선택 문서|학습 목표|핵심 개념|문서별 핵심 요약|추천 학습 순서)\\b)", "");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();
        return cleaned;
    }

    private String buildPrompt(
            String question,
            RagQueryResponse ragResponse,
            LearningMemory memory,
            List<ChatMessage> recentMessages,
            boolean studyCourseMode
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

        if (!studyCourseMode) {
            prompt.append("[Recent Conversation]\n");
            recentMessages.stream()
                    .skip(Math.max(0, recentMessages.size() - 6))
                    .forEach(message -> prompt.append(message.getRole().name()).append(": ").append(message.getContent()).append("\n"));
        }

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
        if (studyCourseMode) {
            prompt.append("8. Do not use markdown heading markers such as #, ##, ###, ####, or bullet decorations for section titles.\n");
            prompt.append("9. Write section titles as plain Korean text, for example: AI가 생성한 통합 학습 코스, 선택 문서, 학습 목표.\n");
        }
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

    private List<Long> resolveDocumentIds(ChatSession session, TutorAskRequest request) {
        List<Long> sessionDocumentIds = chatSessionDocumentRepository.findBySessionIdOrderByIdAsc(session.getId()).stream()
                .map(ChatSessionDocument::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        List<Long> requestedDocumentIds = resolveRequestedDocumentIds(request);
        if (requestedDocumentIds.isEmpty()) {
            return sessionDocumentIds;
        }

        Set<Long> attachedDocumentIds = new LinkedHashSet<>(sessionDocumentIds);
        List<Long> detachedDocumentIds = requestedDocumentIds.stream()
                .filter(documentId -> !attachedDocumentIds.contains(documentId))
                .toList();
        if (!detachedDocumentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected document is not attached to this chat session");
        }

        return requestedDocumentIds;
    }

    private List<Long> resolveRequestedDocumentIds(TutorAskRequest request) {
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            return request.getDocumentIds().stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }

        if (request.getDocumentId() != null) {
            return List.of(request.getDocumentId());
        }

        return List.of();
    }

    private List<RagDocument> findSelectedDocuments(User currentUser, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }

        return documentIds.stream()
                .map(documentId -> currentUser == null
                        ? ragDocumentRepository.findById(documentId)
                        : ragDocumentRepository.findByIdAndUserId(documentId, currentUser.getId()))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private String buildStudyCourseRetrievalQuery(List<RagDocument> selectedDocuments) {
        String documentContext = selectedDocuments == null || selectedDocuments.isEmpty()
                ? "선택된 PDF"
                : selectedDocuments.stream()
                        .map(document -> document.getTitle() + " " + document.getSubject() + " " + document.getUnitName())
                        .collect(java.util.stream.Collectors.joining(" "));
        return documentContext + " 핵심 개념 학습 목표 복습 순서 요약";
    }

    private String buildStudyCourseQuestion(List<RagDocument> selectedDocuments) {
        return buildStudyCourseQuestion();
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
