package com.gyeongtaekim.ai_tutor.service.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionsResponse;
import com.gyeongtaekim.ai_tutor.service.OllamaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class QuestionGenerationService {

    private static final Pattern CONCEPT_WITH_ENGLISH = Pattern.compile("([A-Za-z가-힣][A-Za-z가-힣\\s]{1,40})\\s*\\(([A-Za-z][A-Za-z\\s-]{1,30})\\)");
    private static final Pattern BLANK_SAFE = Pattern.compile("[^A-Za-z가-힣0-9 ]");

    private final QuestionTypeSelector questionTypeSelector;
    private final QuestionPromptFactory questionPromptFactory;
    private final QuestionValidator questionValidator;
    private final QuestionRepairService questionRepairService;
    private final OllamaService ollamaService;
    private final ObjectMapper objectMapper;

    public RagGeneratedQuestionsResponse generate(
            RagDocument document,
            List<DocumentChunk> chunks,
            QuestionGenerationRequest request
    ) {
        QuestionGenerationPlan plan = questionTypeSelector.createPlan(request);
        List<EvidenceUnit> evidenceUnits = extractEvidence(document, chunks);
        List<RagGeneratedQuestionResponse> questions = new ArrayList<>();

        for (int i = 0; i < plan.orderedTypes().size(); i++) {
            QuestionType type = plan.orderedTypes().get(i);
            QuestionDraft draft = generateValidatedDraft(document, evidenceUnits, type, plan.difficulty(), plan.mode(), i + 1);
            questions.add(toResponse(i + 1, draft));
        }

        return new RagGeneratedQuestionsResponse(
                document.getId(),
                document.getTitle(),
                document.getStoredFileName(),
                questions
        );
    }

    private QuestionDraft generateValidatedDraft(
            RagDocument document,
            List<EvidenceUnit> evidenceUnits,
            QuestionType type,
            String difficulty,
            String mode,
            int order
    ) {
        for (int attempt = 0; attempt < 3; attempt++) {
            QuestionDraft draft = attempt == 0
                    ? generateWithLlm(document, evidenceUnits, type, difficulty, mode)
                    : null;
            if (draft == null) {
                draft = generateDeterministic(evidenceUnits, type, difficulty, mode, order + attempt);
            }

            QuestionDraft repaired = questionRepairService.repair(draft);
            QuestionValidationResult validation = questionValidator.validate(repaired);
            if (validation.valid()) {
                return repaired;
            }
        }

        return buildSafeFallback(evidenceUnits, type, difficulty, mode, order);
    }

    private QuestionDraft generateWithLlm(
            RagDocument document,
            List<EvidenceUnit> evidenceUnits,
            QuestionType type,
            String difficulty,
            String mode
    ) {
        if (!ollamaService.isEnabled()) {
            return null;
        }

        String evidenceBlock = evidenceUnits.stream()
                .limit(5)
                .map(item -> "- " + item.concept() + ": " + item.explanation())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        if (evidenceBlock.isBlank()) {
            return null;
        }

        QuestionPromptFactory.PromptPayload prompt = questionPromptFactory.buildPrompt(
                type, document.getTitle(), evidenceBlock, difficulty, mode
        );
        String response = ollamaService.generateJson(prompt.systemPrompt(), prompt.userPrompt());
        if (response == null || response.isBlank()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(response);
            return new QuestionDraft(
                    type,
                    node.path("question").asText(),
                    readStringArray(node.path("choices")),
                    node.path("correctAnswer").asText(),
                    readStringArray(node.path("acceptableAnswers")),
                    node.path("modelAnswer").asText(),
                    node.path("explanation").asText(),
                    node.path("sourceEvidence").asText(),
                    node.path("difficulty").asText(difficulty),
                    readStringArray(node.path("tags")),
                    readMap(node.path("format")),
                    readMap(node.path("meta"))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private QuestionDraft generateDeterministic(
            List<EvidenceUnit> evidenceUnits,
            QuestionType type,
            String difficulty,
            String mode,
            int seed
    ) {
        EvidenceUnit primary = evidenceUnits.get(seed % evidenceUnits.size());
        EvidenceUnit secondary = evidenceUnits.get((seed + 1) % evidenceUnits.size());
        List<String> conceptPool = evidenceUnits.stream()
                .map(EvidenceUnit::concept)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();

        return switch (type) {
            case MULTIPLE_CHOICE -> buildMultipleChoice(primary, conceptPool, difficulty);
            case SHORT_ANSWER -> buildShortAnswer(primary, difficulty, mode);
            case FILL_IN_BLANK -> buildFillInBlank(primary, difficulty);
            case TRUE_FALSE -> buildTrueFalse(primary, difficulty, seed);
            case MATCHING -> buildMatching(evidenceUnits, difficulty);
            case ORDERING -> buildOrdering(evidenceUnits, difficulty);
            case CODE_READING -> buildCodeReading(primary, difficulty);
            case CODE_COMPLETION -> buildCodeCompletion(primary, difficulty);
            case ERROR_DETECTION -> buildErrorDetection(primary, difficulty);
            case COMPARISON -> buildComparison(primary, secondary, difficulty);
            case APPLICATION -> buildApplication(primary, conceptPool, difficulty);
            case MULTI_SELECT -> buildMultiSelect(evidenceUnits, difficulty);
        };
    }

    private QuestionDraft buildMultipleChoice(EvidenceUnit evidence, List<String> conceptPool, String difficulty) {
        String correct = nonBlank(evidence.concept(), "핵심 개념");
        String stem = "다음 설명에 가장 알맞은 개념은 무엇인가?\n" + maskTerm(evidence.explanation(), correct);
        List<String> choices = buildChoices(correct, conceptPool, List.of("상속", "캡슐화", "추상화", "다형성"));
        return baseDraft(
                QuestionType.MULTIPLE_CHOICE,
                stem,
                choices,
                correct,
                List.of(correct, englishAlias(correct)),
                correct,
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("concept-check", correct),
                Map.of(),
                Map.of("concept", correct, "cognitiveLevel", "understand")
        );
    }

    private QuestionDraft buildShortAnswer(EvidenceUnit evidence, String difficulty, String mode) {
        String concept = cleanConcept(nonBlank(evidence.concept(), "핵심 개념"));
        String answerMode = normalizeShortAnswerMode(mode);
        String question = buildShortAnswerQuestion(concept, answerMode);
        String answer = buildShortAnswerModelAnswer(concept, evidence.explanation(), answerMode);
        return baseDraft(
                QuestionType.SHORT_ANSWER,
                question,
                List.of(),
                answer,
                List.of(answer, concept, englishAlias(concept)),
                answer,
                answer,
                evidence.sourceEvidence(),
                difficulty,
                List.of(concept, "short-answer"),
                Map.of(),
                Map.of("concept", concept, "cognitiveLevel", "application".equals(answerMode) ? "apply" : "understand")
        );
    }

    private String normalizeShortAnswerMode(String mode) {
        String normalized = mode == null ? "concept" : mode.trim().toLowerCase(Locale.ROOT);
        if ("application".equals(normalized) || "example".equals(normalized)) {
            return normalized;
        }
        return "concept";
    }

    private String buildShortAnswerQuestion(String concept, String mode) {
        return switch (mode) {
            case "example" -> concept + "의 예시를 들고 그 이유를 설명하세요.";
            case "application" -> concept + " 개념을 실제 예시나 상황에 어떻게 적용할 수 있는지 설명하세요.";
            default -> concept + "가 무엇인지 정의와 핵심 특징을 포함해 설명하세요.";
        };
    }

    private String buildShortAnswerModelAnswer(String concept, String rawExplanation, String mode) {
        String explanation = normalizeShortAnswerEvidence(concept, rawExplanation);
        String particle = subjectParticle(concept);
        if ("application".equals(mode)) {
            return "예를 들어 사용자가 프로그램에서 파일을 읽거나 메모리를 요청하는 상황에서 "
                    + concept + particle + " " + explanation + "는 설명을 바탕으로 필요한 자원 접근을 중재하거나 관리하는 데 쓰인다.";
        }
        if ("example".equals(mode)) {
            return "예를 들어 " + concept + particle + " " + explanation
                    + "는 특징을 보이는 사례이며, 문서의 설명처럼 핵심 역할이 그 개념에 해당하기 때문에 적절한 예시가 된다.";
        }
        return concept + particle + " " + explanation
                + "를 의미하며, 핵심 특징은 문서에서 설명한 역할을 수행한다는 점이다.";
    }

    private String normalizeShortAnswerEvidence(String concept, String rawExplanation) {
        String cleaned = rawExplanation == null ? "" : rawExplanation
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)" + Pattern.quote(concept), "")
                .replaceAll("다음과 같음.*$", "")
                .replaceAll("아래와 같음.*$", "")
                .replaceAll("주요 역할은.*$", "")
                .replaceAll("로의 주요 역할", "핵심 역할")
                .trim();
        if (cleaned.length() < 12 || cleaned.matches(".*(은|는|이|가|을|를|의|로|으로)$")) {
            if (normalize(concept).contains("커널")) {
                return "운영체제의 핵심 부분으로서 프로세스, 메모리, 파일 같은 하드웨어 자원 접근을 중재하고 관리한다";
            }
            return "문서에서 설명한 핵심 역할을 수행하는 개념이다";
        }
        return stripTrailingSentenceEnd(cleaned);
    }

    private String stripTrailingSentenceEnd(String value) {
        return value.replaceAll("[.?!。]+$", "").trim();
    }

    private String cleanConcept(String concept) {
        String cleaned = concept == null ? "" : concept.replaceAll("[\\p{Punct}]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? "핵심 개념" : cleaned;
    }

    private String subjectParticle(String concept) {
        if (concept == null || concept.isBlank()) {
            return "은";
        }
        char last = concept.charAt(concept.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            return ((last - 0xAC00) % 28) == 0 ? "는" : "은";
        }
        return "은";
    }

    private QuestionDraft buildFillInBlank(EvidenceUnit evidence, String difficulty) {
        String concept = nonBlank(evidence.concept(), "핵심 개념");
        String question = maskTerm(evidence.explanation(), concept);
        if (!question.contains("_____")) {
            question = "빈칸에 들어갈 개념을 쓰시오: " + concept + "은(는) _____ 와 관련된 개념이다.";
        }
        return baseDraft(
                QuestionType.FILL_IN_BLANK,
                question,
                List.of(),
                concept,
                List.of(concept, englishAlias(concept)),
                concept,
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("fill-in-blank", concept),
                Map.of("blankCount", 1),
                Map.of("concept", concept, "cognitiveLevel", "remember")
        );
    }

    private QuestionDraft buildTrueFalse(EvidenceUnit evidence, String difficulty, int seed) {
        boolean answerTrue = seed % 2 == 0;
        String concept = nonBlank(evidence.concept(), "핵심 개념");
        String statement = answerTrue
                ? evidence.explanation()
                : evidence.explanation() + " 따라서 이 개념은 전혀 관련이 없다.";
        return baseDraft(
                QuestionType.TRUE_FALSE,
                "다음 문장의 진위를 판단하시오.\n" + statement,
                List.of("true", "false"),
                answerTrue ? "true" : "false",
                List.of(answerTrue ? "true" : "false"),
                answerTrue ? "true" : "false",
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("true-false", concept),
                Map.of(),
                Map.of("concept", concept, "cognitiveLevel", "remember")
        );
    }

    private QuestionDraft buildMatching(List<EvidenceUnit> evidenceUnits, String difficulty) {
        List<EvidenceUnit> picked = evidenceUnits.stream().limit(Math.min(3, evidenceUnits.size())).toList();
        List<String> left = picked.stream().map(EvidenceUnit::concept).toList();
        List<String> right = picked.stream().map(EvidenceUnit::explanation).toList();
        return baseDraft(
                QuestionType.MATCHING,
                "다음 개념과 설명을 서로 짝지으시오.",
                List.of(),
                "각 개념을 해당 설명과 연결",
                left,
                "각 개념을 해당 설명과 연결",
                "문서에 나온 정의와 설명을 기준으로 짝을 맞추면 된다.",
                picked.get(0).sourceEvidence(),
                difficulty,
                List.of("matching"),
                Map.of("leftItems", left, "rightItems", rotate(right)),
                Map.of("cognitiveLevel", "understand")
        );
    }

    private QuestionDraft buildOrdering(List<EvidenceUnit> evidenceUnits, String difficulty) {
        List<String> steps = evidenceUnits.stream().limit(Math.min(3, evidenceUnits.size()))
                .map(EvidenceUnit::explanation)
                .toList();
        return baseDraft(
                QuestionType.ORDERING,
                "다음 설명들을 가장 자연스러운 학습 순서로 배열하시오.",
                List.of(),
                String.join(" -> ", steps),
                steps,
                String.join(" -> ", steps),
                "문서 흐름상 먼저 나오는 설명부터 배열하면 된다.",
                evidenceUnits.get(0).sourceEvidence(),
                difficulty,
                List.of("ordering"),
                Map.of("items", rotate(steps), "orderedItems", steps),
                Map.of("cognitiveLevel", "analyze")
        );
    }

    private QuestionDraft buildCodeReading(EvidenceUnit evidence, String difficulty) {
        String concept = nonBlank(evidence.concept(), "다형성");
        String code = """
                ```java
                class Parent {}
                class Child extends Parent {}

                Parent value = new Child();
                ```
                """;
        return baseDraft(
                QuestionType.CODE_READING,
                "다음 Java 코드는 어떤 개념을 보여주는가?\n" + code,
                List.of(),
                concept,
                List.of(concept, englishAlias(concept)),
                concept,
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("code-reading", concept),
                Map.of("language", "java"),
                Map.of("concept", concept, "cognitiveLevel", "analyze")
        );
    }

    private QuestionDraft buildCodeCompletion(EvidenceUnit evidence, String difficulty) {
        String concept = nonBlank(evidence.concept(), "다형성");
        String code = """
                ```java
                class Parent {}
                class Child extends Parent {}

                _____ value = new Child();
                ```
                """;
        return baseDraft(
                QuestionType.CODE_COMPLETION,
                "빈 코드 자리에 들어갈 가장 알맞은 내용을 쓰시오.\n" + code,
                List.of(),
                "Parent",
                List.of("Parent", "parent"),
                "Parent",
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("code-completion", concept),
                Map.of("language", "java", "blankCount", 1),
                Map.of("concept", concept, "cognitiveLevel", "apply")
        );
    }

    private QuestionDraft buildErrorDetection(EvidenceUnit evidence, String difficulty) {
        String concept = nonBlank(evidence.concept(), "핵심 개념");
        String code = """
                ```java
                class Parent {}
                class Child extends Parent {}

                Child value = new Parent(); // incorrect assignment
                ```
                """;
        return baseDraft(
                QuestionType.ERROR_DETECTION,
                "다음 Java 코드에서 잘못된 점을 찾으시오.\n" + code,
                List.of(),
                "Parent 객체를 Child 참조변수에 대입할 수 없다.",
                List.of("Parent 객체를 Child 참조변수에 대입할 수 없다.", "부모 객체를 자식 타입 변수에 바로 넣을 수 없다."),
                "Parent 객체를 Child 참조변수에 대입할 수 없다.",
                "상속 관계가 있어도 부모 객체를 자식 타입 변수에 그대로 대입할 수는 없다.",
                evidence.sourceEvidence(),
                difficulty,
                List.of("error-detection", concept),
                Map.of("language", "java"),
                Map.of("concept", concept, "cognitiveLevel", "analyze")
        );
    }

    private QuestionDraft buildComparison(EvidenceUnit primary, EvidenceUnit secondary, String difficulty) {
        String left = nonBlank(primary.concept(), "개념 A");
        String right = nonBlank(secondary.concept(), "개념 B");
        return baseDraft(
                QuestionType.COMPARISON,
                left + "와 " + right + "의 차이점을 설명하라.",
                List.of(),
                left + "와 " + right + "은(는) 서로 다른 역할과 정의를 가진다.",
                List.of(left + "와 " + right),
                left + "는 " + primary.explanation() + " / " + right + "는 " + secondary.explanation(),
                "두 개념의 정의를 각각 설명한 뒤 차이점을 정리하면 된다.",
                primary.sourceEvidence(),
                difficulty,
                List.of("comparison", left, right),
                Map.of(),
                Map.of("compareTargets", List.of(left, right), "cognitiveLevel", "analyze")
        );
    }

    private QuestionDraft buildApplication(EvidenceUnit evidence, List<String> conceptPool, String difficulty) {
        String concept = nonBlank(evidence.concept(), "핵심 개념");
        List<String> choices = buildChoices(concept, conceptPool, List.of("상속", "캡슐화", "다형성", "추상화"));
        return baseDraft(
                QuestionType.APPLICATION,
                "다음 상황에서 가장 알맞은 개념을 고르시오.\n상황: " + evidence.explanation(),
                choices,
                concept,
                List.of(concept, englishAlias(concept)),
                concept,
                evidence.explanation(),
                evidence.sourceEvidence(),
                difficulty,
                List.of("application", concept),
                Map.of(),
                Map.of("concept", concept, "cognitiveLevel", "apply")
        );
    }

    private QuestionDraft buildMultiSelect(List<EvidenceUnit> evidenceUnits, String difficulty) {
        List<String> concepts = evidenceUnits.stream().map(EvidenceUnit::concept).filter(value -> !value.isBlank()).distinct().limit(2).toList();
        String first = concepts.isEmpty() ? "상속" : concepts.get(0);
        String second = concepts.size() > 1 ? concepts.get(1) : "다형성";
        List<String> choices = new ArrayList<>(List.of(first, second, "반복문", "파일 시스템"));
        return baseDraft(
                QuestionType.MULTI_SELECT,
                "문서 내용과 직접 관련된 개념을 모두 고르시오.",
                choices,
                first + ", " + second,
                List.of(first, second),
                first + ", " + second,
                "문서에서 직접 설명된 개념들을 고르면 된다.",
                evidenceUnits.get(0).sourceEvidence(),
                difficulty,
                List.of("multi-select", first, second),
                Map.of("selectionCount", 2),
                Map.of("cognitiveLevel", "understand")
        );
    }

    private QuestionDraft buildSafeFallback(List<EvidenceUnit> evidenceUnits, QuestionType type, String difficulty, String mode, int seed) {
        return generateDeterministic(evidenceUnits, type == QuestionType.MATCHING ? QuestionType.SHORT_ANSWER : type, difficulty, mode, seed);
    }

    private RagGeneratedQuestionResponse toResponse(int order, QuestionDraft draft) {
        return new RagGeneratedQuestionResponse(
                order,
                draft.type().apiValue(),
                draft.question(),
                draft.choices(),
                draft.correctAnswer(),
                draft.acceptableAnswers(),
                draft.modelAnswer(),
                draft.explanation(),
                draft.sourceEvidence(),
                draft.difficulty(),
                draft.tags(),
                draft.format(),
                draft.meta()
        );
    }

    private List<EvidenceUnit> extractEvidence(RagDocument document, List<DocumentChunk> chunks) {
        List<EvidenceUnit> items = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            for (String sentence : splitSentences(chunk.getChunkText())) {
                if (sentence.isBlank()) {
                    continue;
                }
                String concept = inferConcept(sentence, document.getTitle());
                items.add(new EvidenceUnit(
                        concept,
                        sentence,
                        chunk.getDocument().getTitle() + " [chunk " + chunk.getChunkIndex() + "]"
                ));
            }
        }
        if (items.isEmpty()) {
            items.add(new EvidenceUnit(
                    inferConcept(document.getExtractedText(), document.getTitle()),
                    document.getExtractedText(),
                    document.getTitle() + " [chunk 0]"
            ));
        }
        return items;
    }

    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("(?<=[.!?])\\s+|\\R+"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(12)
                .toList();
    }

    private String inferConcept(String text, String title) {
        String normalized = text == null ? "" : text.trim();
        Matcher matcher = CONCEPT_WITH_ENGLISH.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String[] words = normalized.split("\\s+");
        if (words.length >= 2) {
            String candidate = (words[0] + " " + words[1]).trim();
            if (candidate.length() <= 30 && Character.isLetter(candidate.charAt(0))) {
                return sanitize(candidate);
            }
        }
        return title == null ? "핵심 개념" : title.replace(".pdf", "").trim();
    }

    private List<String> buildChoices(String correct, List<String> conceptPool, List<String> fallbackPool) {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(correct);
        for (String value : conceptPool) {
            if (choices.size() == 4) {
                break;
            }
            if (!normalize(value).equals(normalize(correct))) {
                choices.add(value);
            }
        }
        for (String value : fallbackPool) {
            if (choices.size() == 4) {
                break;
            }
            if (!normalize(value).equals(normalize(correct))) {
                choices.add(value);
            }
        }
        List<String> ordered = new ArrayList<>(choices);
        Collections.rotate(ordered, 1);
        return ordered.stream().limit(4).toList();
    }

    private List<String> rotate(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        if (copy.size() > 1) {
            Collections.rotate(copy, 1);
        }
        return copy;
    }

    private String maskTerm(String sentence, String term) {
        String masked = sentence.replaceAll("(?i)" + Pattern.quote(term), "_____");
        String alias = englishAlias(term);
        if (!alias.equals(term)) {
            masked = masked.replaceAll("(?i)" + Pattern.quote(alias), "");
        }
        masked = BLANK_SAFE.matcher(masked).replaceAll(" ").replaceAll("\\s{2,}", " ").trim();
        return masked.contains("_____") ? masked : "_____ " + masked;
    }

    private String englishAlias(String value) {
        if (value == null) {
            return "";
        }
        Matcher matcher = CONCEPT_WITH_ENGLISH.matcher(value);
        if (matcher.find()) {
            return matcher.group(2).trim();
        }
        return value;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^A-Za-z가-힣0-9 ]", "").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private QuestionDraft baseDraft(
            QuestionType type,
            String question,
            List<String> choices,
            String correctAnswer,
            List<String> acceptableAnswers,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty,
            List<String> tags,
            Map<String, Object> format,
            Map<String, Object> meta
    ) {
        return new QuestionDraft(
                type,
                question,
                choices,
                correctAnswer,
                acceptableAnswers,
                modelAnswer,
                explanation,
                sourceEvidence,
                difficulty,
                tags,
                format,
                meta
        );
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText().trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, Object> readMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private record EvidenceUnit(String concept, String explanation, String sourceEvidence) {
    }
}
