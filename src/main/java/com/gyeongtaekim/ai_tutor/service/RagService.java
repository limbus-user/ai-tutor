package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionsResponse;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.repository.DocumentChunkRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagService {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+[.)]?\\s*");
    private static final Pattern BRACKET_HEADER = Pattern.compile("^\\[[^\\]]+\\]\\s*");
    private static final Pattern CONCEPT_WITH_ENGLISH = Pattern.compile("([\\p{IsAlphabetic}\\p{IsDigit}가-힣 ]{2,40})\\s*\\(([A-Za-z][A-Za-z\\s-]{1,30})\\)");
    private static final Pattern ENGLISH_TERM = Pattern.compile("^[A-Za-z][A-Za-z\\s-]{1,40}$");
    private static final Pattern NUMBERED_SECTION_BOUNDARY = Pattern.compile("\\s+(?=\\d+[.)]\\s*)");
    private static final int RETRIEVAL_TOP_K = 4;
    private static final int EMBEDDING_SEARCH_WINDOW = 10;
    private static final double EMBEDDING_SCORE_WEIGHT = 0.65;
    private static final double KEYWORD_SCORE_WEIGHT = 0.35;
    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "뭐", "무엇", "무슨", "설명", "쉽게", "알려", "알려줘", "말해", "말해줘", "질문", "핵심", "내용", "정리",
            "왜", "어떻게", "관련", "이해", "주제", "pdf", "문서", "업로드한", "학습", "자료"
    );

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.embedding.model:text-embedding-3-small}")
    private String openAiEmbeddingModelName;

    private final RagDocumentRepository ragDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final OllamaService ollamaService;

    private final InMemoryEmbeddingStore<DocumentChunk> embeddingStore = new InMemoryEmbeddingStore<>();
    private volatile boolean embeddingsInitialized = false;
    private static final List<String> ALLOWED_GENERATION_TYPES = List.of("mixed", "multiple_choice", "short_answer", "ox");

    public RagDocumentUploadResponse processPdf(
            MultipartFile file,
            String subject,
            String unitName,
            String trustLevel
    ) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        Path uploadDir = Paths.get(uploadPath);
        Files.createDirectories(uploadDir);

        String storedFileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = uploadDir.resolve(storedFileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        String text = extractTextFromBytes(file.getBytes());
        List<TextSegment> segments = splitIntoSegments(text);

        RagDocument ragDocument = ragDocumentRepository.save(new RagDocument(
                file.getOriginalFilename(),
                RagDocument.SourceType.PDF,
                defaultValue(trustLevel, "internal"),
                defaultValue(subject, "general"),
                defaultValue(unitName, "general"),
                storedFileName,
                text
        ));

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String metadata = "subject=" + ragDocument.getSubject()
                    + ", unit=" + ragDocument.getUnitName()
                    + ", source=" + ragDocument.getStoredFileName()
                    + ", chunkIndex=" + i;
            chunks.add(new DocumentChunk(ragDocument, i, segments.get(i).text(), metadata));
        }
        documentChunkRepository.saveAll(chunks);
        addChunksToEmbeddingStore(chunks);

        return new RagDocumentUploadResponse(ragDocument, chunks.size());
    }

    public RagQueryResponse query(String query) {
        return query(query, null);
    }

    public RagQueryResponse query(String query, Long documentId) {
        List<DocumentChunk> allChunks = documentId == null
                ? documentChunkRepository.findAll()
                : documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        if (allChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "검색 가능한 문서가 없습니다. 먼저 /api/rag/upload 로 PDF를 업로드해 주세요.",
                    List.of()
            );
        }

        List<DocumentChunk> topChunks = retrieveRelevantChunks(query, allChunks);
        if (topChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "질문과 직접 관련된 문서 근거를 찾지 못했습니다. 질문을 더 구체적으로 하거나 관련 PDF를 먼저 업로드해 주세요.",
                    List.of()
            );
        }

        List<String> evidence = buildAnswerEvidence(query, topChunks);
        List<String> sources = topChunks.stream()
                .map(chunk -> chunk.getDocument().getTitle() + " [chunk " + chunk.getChunkIndex() + "]")
                .distinct()
                .toList();

        String answer = evidence.isEmpty()
                ? topChunks.stream().map(DocumentChunk::getChunkText).collect(Collectors.joining("\n\n"))
                : String.join("\n", evidence);

        return new RagQueryResponse(query, answer, sources);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String fileName) {
        if (documentId != null) {
            return generateQuestions(documentId);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId or fileName is required");
        }

        RagDocument document = ragDocumentRepository.findByStoredFileName(fileName)
                .orElseGet(() -> loadLegacyDocument(fileName));

        return buildGeneratedQuestionsResponse(document);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String fileName, String type, Integer count) {
        return generateQuestions(documentId, List.of(), fileName, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(
            Long documentId,
            List<Long> documentIds,
            String fileName,
            String type,
            Integer count
    ) {
        String normalizedType = normalizeGenerationType(type);
        int normalizedCount = normalizeCount(count);

        List<Long> selectedDocumentIds = documentIds == null
                ? List.of()
                : documentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (!selectedDocumentIds.isEmpty()) {
            return generateQuestions(selectedDocumentIds, normalizedType, normalizedCount);
        }
        if (documentId != null) {
            return generateQuestions(documentId, normalizedType, normalizedCount);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId or fileName is required");
        }

        RagDocument document = ragDocumentRepository.findByStoredFileName(fileName)
                .orElseGet(() -> loadLegacyDocument(fileName));

        return buildGeneratedQuestionsResponse(document, normalizedType, normalizedCount);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId) {
        RagDocument document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        return buildGeneratedQuestionsResponse(document);
    }

    public List<RagDocumentSummaryResponse> getDocuments() {
        return ragDocumentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(RagDocumentSummaryResponse::new)
                .toList();
    }

    public RagDocument getDocument(Long documentId) {
        return ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    public Path resolveStoredFilePath(Long documentId) {
        RagDocument document = getDocument(documentId);
        Path path = Paths.get(uploadPath).resolve(document.getStoredFileName()).normalize();
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file not found");
        }
        return path;
    }

    public RagDocumentSummaryResponse renameDocument(Long documentId, String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }

        RagDocument document = getDocument(documentId);
        document.updateTitle(title.trim());
        return new RagDocumentSummaryResponse(ragDocumentRepository.save(document));
    }

    public void deleteDocument(Long documentId) {
        RagDocument document = getDocument(documentId);
        Path path = Paths.get(uploadPath).resolve(document.getStoredFileName()).normalize();
        documentChunkRepository.deleteAll(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId));
        ragDocumentRepository.delete(document);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete stored file");
        }
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String type, Integer count) {
        RagDocument document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        return buildGeneratedQuestionsResponse(document, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(List<Long> documentIds, String type, Integer count) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentIds are required");
        }

        Map<Long, RagDocument> documentsById = ragDocumentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(RagDocument::getId, document -> document));
        List<RagDocument> documents = documentIds.stream()
                .distinct()
                .map(documentId -> {
                    RagDocument document = documentsById.get(documentId);
                    if (document == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId);
                    }
                    return document;
                })
                .toList();
        List<DocumentChunk> chunks = documents.stream()
                .flatMap(document -> documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId()).stream())
                .toList();

        return buildGeneratedQuestionsResponse(documents, chunks, type, count);
    }

    private RagGeneratedQuestionsResponse buildGeneratedQuestionsResponse(RagDocument document) {
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        List<DocumentChunk> representativeChunks = selectRepresentativeChunks(chunks, 6);
        String representativeText = representativeChunks.stream()
                .map(DocumentChunk::getChunkText)
                .collect(Collectors.joining("\n"));
        List<RagGeneratedQuestionResponse> questions = buildQuestionSet(representativeText, representativeChunks);

        return new RagGeneratedQuestionsResponse(
                document.getId(),
                document.getTitle(),
                document.getStoredFileName(),
                questions
        );
    }

    private RagGeneratedQuestionsResponse buildGeneratedQuestionsResponse(RagDocument document, String type, int count) {
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        return buildGeneratedQuestionsResponse(List.of(document), chunks, type, count);
    }

    private RagGeneratedQuestionsResponse buildGeneratedQuestionsResponse(
            List<RagDocument> documents,
            List<DocumentChunk> chunks,
            String type,
            int count
    ) {
        List<DocumentChunk> representativeChunks = selectRepresentativeChunks(chunks, Math.max(count + 2, 4));
        String representativeText = representativeChunks.stream()
                .map(DocumentChunk::getChunkText)
                .collect(Collectors.joining("\n"));
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(representativeText, representativeChunks);
        List<String> evidenceSentences = collectEvidenceSentences(representativeText, representativeChunks);
        List<RagGeneratedQuestionResponse> questions = buildAdaptiveQuestionSet(representativeText, conceptEvidence, evidenceSentences, type, count);
        RagDocument primaryDocument = documents.get(0);

        return new RagGeneratedQuestionsResponse(
                primaryDocument.getId(),
                documents.stream().map(RagDocument::getTitle).collect(Collectors.joining(", ")),
                primaryDocument.getStoredFileName(),
                questions
        );
    }

    private List<RagGeneratedQuestionResponse> buildQuestionSet(String rawText, List<DocumentChunk> chunks) {
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(rawText, chunks);
        List<String> evidenceSentences = collectEvidenceSentences(rawText, chunks);

        if (conceptEvidence.isEmpty() && evidenceSentences.isEmpty()) {
            return List.of(new RagGeneratedQuestionResponse(
                    1,
                    "이 PDF에서 핵심 개념을 설명해 보세요.",
                    "텍스트가 포함된 PDF를 다시 업로드한 뒤 문제를 생성해 주세요.",
                    "이미지형 PDF이거나 추출 가능한 텍스트가 거의 없으면 학습용 문제를 만들 수 없습니다."
            ));
        }

        List<RagGeneratedQuestionResponse> llmQuestions = buildQuestionSetWithOllama(rawText, conceptEvidence, evidenceSentences);
        if (!llmQuestions.isEmpty()) {
            return llmQuestions;
        }

        List<RagGeneratedQuestionResponse> questions = new ArrayList<>();
        questions.add(new RagGeneratedQuestionResponse(
                1,
                "이 문서의 핵심 주제를 한 문장으로 요약하세요.",
                buildTopicSummary(conceptEvidence, evidenceSentences),
                "문서 전체를 한 문장으로 정리하면 핵심 개념을 얼마나 이해했는지 빠르게 확인할 수 있습니다."
        ));

        List<ConceptEvidence> topConcepts = conceptEvidence.stream().limit(4).toList();
        for (ConceptEvidence evidence : topConcepts) {
            questions.add(new RagGeneratedQuestionResponse(
                    questions.size() + 1,
                    "'" + evidence.concept() + "' 개념을 쉽게 설명해 보세요.",
                    evidence.explanation(),
                    "'" + evidence.concept() + "'의 정의와 역할을 직접 설명할 수 있어야 문서 내용을 이해했다고 볼 수 있습니다."
            ));
            if (questions.size() >= 5) {
                break;
            }
        }

        int sentenceIndex = 0;
        while (questions.size() < 5 && sentenceIndex < evidenceSentences.size()) {
            String sentence = evidenceSentences.get(sentenceIndex++);
            if (topConcepts.stream().anyMatch(concept -> sentence.contains(concept.explanation()))) {
                continue;
            }
            questions.add(new RagGeneratedQuestionResponse(
                    questions.size() + 1,
                    "다음 학습 내용을 자신의 말로 다시 설명해 보세요.",
                    sentence,
                    "문장을 그대로 외우는 것보다 핵심 의미를 재구성해서 말하는 연습이 이해에 더 도움이 됩니다."
            ));
        }

        while (questions.size() < 5) {
            String fallback = !evidenceSentences.isEmpty()
                    ? evidenceSentences.get(Math.min(sentenceIndex, evidenceSentences.size() - 1))
                    : buildTopicSummary(conceptEvidence, evidenceSentences);
            questions.add(new RagGeneratedQuestionResponse(
                    questions.size() + 1,
                    "문서에서 중요하다고 생각하는 내용을 예시와 함께 설명해 보세요.",
                    fallback,
                    "핵심 문장을 예시와 연결해 설명하면 개념을 실제로 이해했는지 확인할 수 있습니다."
            ));
        }

        return questions;
    }

    private List<RagGeneratedQuestionResponse> buildQuestionSetWithOllama(
            String rawText,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences
    ) {
        if (!ollamaService.isEnabled()) {
            return List.of();
        }

        String evidenceBlock = conceptEvidence.stream()
                .limit(6)
                .map(item -> "- " + item.concept() + ": " + item.explanation())
                .collect(Collectors.joining("\n"));
        String sentenceBlock = evidenceSentences.stream()
                .limit(6)
                .map(sentence -> "- " + sentence)
                .collect(Collectors.joining("\n"));
        String rawExcerpt = abbreviate(rawText, 1800);

        String response = ollamaService.generateJson(
                """
                You create Korean study questions from PDF evidence.
                Return strict JSON only.
                Do not use markdown.
                Do not add explanations outside JSON.
        
                The JSON shape must be:
                {"questions":[{"question":"...","modelAnswer":"...","explanation":"..."}]}
        
                Make exactly 5 items.
                Do not invent facts outside the evidence.
        
                Question quality rules:
                - All questions must be written in Korean.
                - Do not create a short_answer question that asks whether a statement is true or false.
                - Do not create a short_answer question that can be answered with only "O", "X", "true", "false", "맞다", "틀리다", "예", or "아니오".
                - Short answer questions must ask the learner to explain a concept, compare two concepts, describe a relationship, give a reason, or connect an example to a concept.
                - For short answer questions, the modelAnswer must be a complete explanatory sentence, not just a keyword.
                - Avoid questions that start with "다음 설명이 맞으면", "맞는가", "옳은가", "참인가", "O/X".
                - If a question asks for true/false judgment, it must not be generated as short_answer.
                - Do not repeat the same question pattern.
                """,
                """
                [Concept Evidence]
                %s
        
                [Supporting Sentences]
                %s
        
                [Raw Excerpt]
                %s
                """.formatted(evidenceBlock, sentenceBlock, rawExcerpt)
        );

        if (response == null || response.isBlank()) {
            return List.of();
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(extractJsonObject(response));
            com.fasterxml.jackson.databind.JsonNode questionsNode = root.get("questions");
            if (questionsNode == null || !questionsNode.isArray()) {
                return List.of();
            }

            List<RagGeneratedQuestionResponse> questions = new ArrayList<>();
            int order = 1;
            for (com.fasterxml.jackson.databind.JsonNode node : questionsNode) {
                String question = normalizeWhitespace(node.path("question").asText());
                String modelAnswer = normalizeWhitespace(node.path("modelAnswer").asText());
                if (modelAnswer.isBlank()) {
                    modelAnswer = normalizeWhitespace(node.path("answer").asText());
                }
                String explanation = normalizeWhitespace(node.path("explanation").asText());
                if (question.isBlank() || modelAnswer.isBlank() || explanation.isBlank()) {
                    continue;
                }
                questions.add(new RagGeneratedQuestionResponse(order++, question, modelAnswer, explanation));
                if (questions.size() == 5) {
                    break;
                }
            }

            return questions.isEmpty() ? List.of() : questions;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildTopicSummary(List<ConceptEvidence> conceptEvidence, List<String> evidenceSentences) {
        if (conceptEvidence.size() >= 2) {
            return "이 문서는 " + conceptEvidence.get(0).concept() + "와(과) " + conceptEvidence.get(1).concept()
                    + " 같은 핵심 개념을 중심으로 각 개념의 정의와 역할을 설명합니다.";
        }
        if (conceptEvidence.size() == 1) {
            return "이 문서는 " + conceptEvidence.get(0).concept() + "의 개념과 의미를 설명합니다.";
        }
        return evidenceSentences.isEmpty()
                ? "문서에서 핵심 개념을 추출하지 못했습니다."
                : evidenceSentences.get(0);
    }

    private List<String> buildAnswerEvidence(String query, List<DocumentChunk> chunks) {
        List<String> mergedLines = chunks.stream()
                .flatMap(chunk -> splitLines(chunk.getChunkText()).stream())
                .toList();
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(String.join("\n", mergedLines), chunks);
        List<String> evidenceSentences = collectEvidenceSentences(String.join("\n", mergedLines), chunks);
        List<String> tokens = extractSearchTokens(query);

        List<String> answers = new ArrayList<>();
        conceptEvidence.stream()
                .sorted(Comparator.comparingInt((ConceptEvidence evidence) -> scoreConceptEvidence(tokens, evidence)).reversed())
                .filter(evidence -> scoreConceptEvidence(tokens, evidence) > 0)
                .limit(3)
                .forEach(evidence -> answers.add(evidence.concept() + ": " + evidence.explanation()));

        if (!answers.isEmpty()) {
            return answers;
        }

        evidenceSentences.stream()
                .sorted(Comparator.comparingInt((String sentence) -> scoreSentence(tokens, sentence)).reversed())
                .filter(sentence -> scoreSentence(tokens, sentence) > 0)
                .limit(3)
                .forEach(answers::add);

        return answers;
    }

    private List<ConceptEvidence> extractConceptEvidence(String rawText, List<DocumentChunk> chunks) {
        List<String> lines = !splitLines(rawText).isEmpty()
                ? splitLines(rawText)
                : chunks.stream().flatMap(chunk -> splitLines(chunk.getChunkText()).stream()).toList();

        List<ConceptEvidence> evidence = new ArrayList<>();
        String pendingConcept = null;
        StringBuilder pendingExplanation = new StringBuilder();

        for (String line : lines) {
            String cleaned = cleanLine(line);
            if (cleaned.isBlank()) {
                continue;
            }

            InlineConcept inlineConcept = extractInlineConcept(cleaned);
            if (inlineConcept != null) {
                flushConceptEvidence(evidence, pendingConcept, pendingExplanation);
                pendingConcept = null;
                pendingExplanation.setLength(0);
                evidence.add(new ConceptEvidence(inlineConcept.concept(), inlineConcept.explanation()));
                continue;
            }

            if (looksLikeConceptHeading(cleaned)) {
                flushConceptEvidence(evidence, pendingConcept, pendingExplanation);
                pendingConcept = normalizeConcept(cleaned);
                pendingExplanation.setLength(0);
                continue;
            }

            if (pendingConcept != null) {
                if (pendingExplanation.length() > 0) {
                    pendingExplanation.append(' ');
                }
                pendingExplanation.append(cleanSentence(cleaned));
                if (isCompleteExplanation(pendingExplanation.toString())) {
                    flushConceptEvidence(evidence, pendingConcept, pendingExplanation);
                    pendingConcept = null;
                }
            }
        }

        flushConceptEvidence(evidence, pendingConcept, pendingExplanation);

        Map<String, ConceptEvidence> deduplicated = new LinkedHashMap<>();
        for (ConceptEvidence item : evidence) {
            if (!deduplicated.containsKey(item.concept()) && !item.explanation().isBlank()) {
                deduplicated.put(item.concept(), item);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private void flushConceptEvidence(List<ConceptEvidence> target, String concept, StringBuilder explanation) {
        if (concept == null || concept.isBlank()) {
            return;
        }
        String cleanedExplanation = cleanSentence(explanation.toString());
        if (cleanedExplanation.isBlank()) {
            return;
        }
        target.add(new ConceptEvidence(concept, cleanedExplanation));
        explanation.setLength(0);
    }

    private InlineConcept extractInlineConcept(String line) {
        Matcher matcher = CONCEPT_WITH_ENGLISH.matcher(line);
        if (matcher.find()) {
            String concept = normalizeConcept(matcher.group(1));
            String rest = cleanSentence(line.substring(matcher.end()).trim());
            if (isUsableConcept(concept) && looksExplanatory(rest)) {
                return new InlineConcept(concept, rest);
            }
        }

        int divider = findInlineDivider(line);
        if (divider > 0) {
            String concept = normalizeConcept(line.substring(0, divider).trim());
            String explanation = cleanSentence(line.substring(divider).trim());
            if (isUsableConcept(concept) && looksExplanatory(explanation)) {
                return new InlineConcept(concept, explanation);
            }
        }

        return null;
    }

    private int findInlineDivider(String line) {
        List<Pattern> patterns = List.of(
                Pattern.compile("^(.{2,24}?)(은|는|이란|란)\\s+.+$"),
                Pattern.compile("^(.{2,24}?)\\s+(is|are)\\s+.+$", Pattern.CASE_INSENSITIVE)
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1).length();
            }
        }

        return -1;
    }

    private boolean looksLikeConceptHeading(String line) {
        if (looksExplanatory(line)) {
            return false;
        }
        String normalized = normalizeConcept(line);
        if (!isUsableConcept(normalized)) {
            return false;
        }
        return normalized.length() <= 30;
    }

    private boolean looksExplanatory(String line) {
        String cleaned = cleanSentence(line);
        if (cleaned.isBlank()) {
            return false;
        }
        String lower = cleaned.toLowerCase(Locale.ROOT);
        return cleaned.length() >= 18
                || cleaned.contains("이다")
                || cleaned.contains("입니다")
                || cleaned.contains("의미")
                || cleaned.contains("능력")
                || cleaned.contains("개념")
                || cleaned.contains("설계도")
                || cleaned.contains("재사용")
                || cleaned.contains("참조")
                || cleaned.contains("ability")
                || cleaned.contains("means")
                || lower.contains("works")
                || lower.contains("allows")
                || lower.contains("used");
    }

    private boolean isCompleteExplanation(String explanation) {
        return explanation.length() >= 25
                || explanation.endsWith(".")
                || explanation.endsWith("다")
                || explanation.endsWith("니다");
    }

    private List<String> collectEvidenceSentences(String rawText, List<DocumentChunk> chunks) {
        String source = rawText == null || rawText.isBlank()
                ? chunks.stream().map(DocumentChunk::getChunkText).collect(Collectors.joining("\n"))
                : rawText;

        LinkedHashSet<String> sentences = new LinkedHashSet<>();
        String[] pieces = source
                .replace("\r", "\n")
                .split("\\n+|(?<=[.!?])\\s+");

        for (String piece : pieces) {
            String cleaned = cleanSentence(piece);
            if (looksExplanatory(cleaned)) {
                sentences.add(cleaned);
            }
        }
        return new ArrayList<>(sentences);
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = NUMBERED_SECTION_BOUNDARY.matcher(text.replace("\r", "\n")).replaceAll("\n");
        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\\n+")) {
            String cleaned = cleanLine(line);
            if (!cleaned.isBlank()) {
                lines.add(cleaned);
            }
        }
        return lines;
    }

    private String cleanLine(String line) {
        String cleaned = normalizeWhitespace(line);
        cleaned = BRACKET_HEADER.matcher(cleaned).replaceFirst("");
        cleaned = LEADING_NUMBER.matcher(cleaned).replaceFirst("");
        cleaned = cleaned.replaceAll("^[Ÿ•·▪◦●○■□◆◇▶▷►※]+\\s*", "");
        cleaned = cleaned.replaceAll("^[-:]+\\s*", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String cleanSentence(String sentence) {
        String cleaned = cleanLine(sentence);
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String normalizeConcept(String value) {
        String concept = normalizeWhitespace(value)
                .replaceAll("\\([^)]+\\)", "")
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣 ]", "")
                .trim()
                .replaceAll("\\s+", " ");
        String[] words = concept.split("\\s+");
        if (words.length >= 2) {
            String lastWord = words[words.length - 1];
            for (int i = 0; i < words.length - 1; i++) {
                if (words[i].equals(lastWord)) {
                    concept = String.join(" ", java.util.Arrays.copyOf(words, words.length - 1));
                    break;
                }
            }
        }
        return concept;
    }

    private boolean isUsableConcept(String concept) {
        if (concept == null || concept.isBlank()) {
            return false;
        }
        if (concept.length() < 2 || concept.length() > 40) {
            return false;
        }
        if (ENGLISH_TERM.matcher(concept).matches() && concept.split("\\s+").length > 4) {
            return false;
        }
        return !Set.of(
                "학습 데이터", "문서", "내용", "설명", "핵심 개념", "중요 개념", "질문", "주제", "자료", "pdf"
        ).contains(concept.toLowerCase(Locale.ROOT));
    }

    private List<DocumentChunk> retrieveRelevantChunks(String query, List<DocumentChunk> allChunks) {
        List<String> tokens = extractSearchTokens(query);
        Map<Long, Double> embeddingScores = retrieveWithEmbeddings(query, allChunks);

        List<ScoredChunk> ranked = allChunks.stream()
                .map(chunk -> new ScoredChunk(
                        chunk,
                        computeHybridChunkScore(chunk, tokens, embeddingScores.getOrDefault(chunk.getId(), 0.0))
                ))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();

        if (ranked.isEmpty()) {
            return List.of();
        }

        return diversifyTopChunks(ranked, RETRIEVAL_TOP_K);
    }

    private Map<Long, Double> retrieveWithEmbeddings(String query, List<DocumentChunk> candidateChunks) {
        if (!embeddingEnabled()) {
            return Map.of();
        }

        initializeEmbeddingsIfNeeded();

        try {
            Set<Long> candidateIds = candidateChunks.stream()
                    .map(DocumentChunk::getId)
                    .collect(Collectors.toSet());
            EmbeddingModel embeddingModel = createEmbeddingModel();
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = new EmbeddingSearchRequest(queryEmbedding, EMBEDDING_SEARCH_WINDOW, 0.45, null);

            return embeddingStore.search(request).matches().stream()
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<DocumentChunk>::score).reversed())
                    .filter(match -> candidateIds.contains(match.embedded().getId()))
                    .collect(Collectors.toMap(
                            match -> match.embedded().getId(),
                            EmbeddingMatch::score,
                            Math::max,
                            LinkedHashMap::new
                    ));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private synchronized void initializeEmbeddingsIfNeeded() {
        if (embeddingsInitialized || !embeddingEnabled()) {
            return;
        }

        List<DocumentChunk> chunks = documentChunkRepository.findAll();
        addChunksToEmbeddingStore(chunks);
        embeddingsInitialized = true;
    }

    private void addChunksToEmbeddingStore(List<DocumentChunk> chunks) {
        if (chunks.isEmpty() || !embeddingEnabled()) {
            return;
        }

        try {
            EmbeddingModel embeddingModel = createEmbeddingModel();
            List<TextSegment> segments = chunks.stream()
                    .map(chunk -> TextSegment.from(chunk.getChunkText()))
                    .toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, chunks);
            embeddingsInitialized = true;
        } catch (Exception ignored) {
            embeddingsInitialized = false;
        }
    }

    private EmbeddingModel createEmbeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiApiKey)
                .modelName(openAiEmbeddingModelName)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private boolean embeddingEnabled() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    private RagDocument loadLegacyDocument(String fileName) {
        try {
            Path filePath = Paths.get(uploadPath, fileName);
            if (!Files.exists(filePath)) {
                throw new IOException("File not found: " + fileName);
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String text = extractTextFromBytes(bytes);
            RagDocument ragDocument = ragDocumentRepository.save(new RagDocument(
                    fileName,
                    RagDocument.SourceType.PDF,
                    "legacy-upload",
                    "general",
                    "general",
                    fileName,
                    text
            ));

            List<TextSegment> segments = splitIntoSegments(text);
            List<DocumentChunk> chunks = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                chunks.add(new DocumentChunk(ragDocument, i, segments.get(i).text(), "source=" + fileName + ", chunkIndex=" + i));
            }
            documentChunkRepository.saveAll(chunks);
            addChunksToEmbeddingStore(chunks);
            return ragDocument;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private List<TextSegment> splitIntoSegments(String text) {
        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        return splitter.split(document);
    }

    private int scoreChunk(String query, String chunkText) {
        List<String> tokens = extractSearchTokens(query);
        int score = 0;
        String lowerChunk = chunkText.toLowerCase(Locale.ROOT);

        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            if (lowerChunk.contains(token.toLowerCase(Locale.ROOT))) {
                score += 3;
            }
        }

        return score;
    }

    private double computeHybridChunkScore(DocumentChunk chunk, List<String> tokens, double embeddingScore) {
        int keywordScore = scoreChunkTokens(tokens, chunk.getChunkText());
        int conceptScore = scoreChunkConceptCoverage(tokens, chunk.getChunkText());
        int sentenceScore = scoreBestSentence(tokens, collectEvidenceSentences(chunk.getChunkText(), List.of(chunk)));
        int metadataScore = scoreChunkMetadata(tokens, chunk);

        double normalizedKeyword = Math.min(1.0, keywordScore / 12.0);
        double normalizedConcept = Math.min(1.0, conceptScore / 10.0);
        double normalizedSentence = Math.min(1.0, sentenceScore / 8.0);
        double normalizedMetadata = Math.min(1.0, metadataScore / 4.0);
        double lexicalScore = (normalizedKeyword * 0.45) + (normalizedConcept * 0.30) + (normalizedSentence * 0.20) + (normalizedMetadata * 0.05);

        return (embeddingScore * EMBEDDING_SCORE_WEIGHT) + (lexicalScore * KEYWORD_SCORE_WEIGHT);
    }

    private int scoreChunkTokens(List<String> tokens, String chunkText) {
        int score = 0;
        String lowerChunk = chunkText.toLowerCase(Locale.ROOT);

        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if (lowerChunk.contains(lowerToken)) {
                score += 3;
            }
            if (lowerChunk.startsWith(lowerToken) || lowerChunk.contains(lowerToken + "은") || lowerChunk.contains(lowerToken + "는")) {
                score += 2;
            }
        }

        return score;
    }

    private int scoreChunkConceptCoverage(List<String> tokens, String chunkText) {
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(chunkText, List.of());
        int best = 0;
        for (ConceptEvidence evidence : conceptEvidence) {
            best = Math.max(best, scoreConceptEvidence(tokens, evidence));
        }
        return best;
    }

    private int scoreBestSentence(List<String> tokens, List<String> evidenceSentences) {
        return evidenceSentences.stream()
                .mapToInt(sentence -> scoreSentence(tokens, sentence))
                .max()
                .orElse(0);
    }

    private int scoreChunkMetadata(List<String> tokens, DocumentChunk chunk) {
        String metadata = normalizeWhitespace(chunk.getMetadata()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if (metadata.contains(lowerToken)) {
                score += 2;
            }
        }
        return score;
    }

    private List<DocumentChunk> diversifyTopChunks(List<ScoredChunk> ranked, int limit) {
        List<DocumentChunk> selected = new ArrayList<>();
        Set<Long> seenChunkIds = new LinkedHashSet<>();
        Set<String> seenSignatures = new LinkedHashSet<>();

        for (ScoredChunk scored : ranked) {
            if (selected.size() >= limit) {
                break;
            }

            DocumentChunk chunk = scored.chunk();
            if (!seenChunkIds.add(chunk.getId())) {
                continue;
            }

            String signature = buildChunkSignature(chunk);
            boolean tooSimilar = seenSignatures.stream().anyMatch(existing -> existing.equals(signature));
            if (tooSimilar) {
                continue;
            }

            selected.add(chunk);
            seenSignatures.add(signature);
        }

        return selected;
    }

    private String buildChunkSignature(DocumentChunk chunk) {
        List<String> sentences = collectEvidenceSentences(chunk.getChunkText(), List.of(chunk));
        if (!sentences.isEmpty()) {
            return normalizeWhitespace(sentences.get(0)).toLowerCase(Locale.ROOT);
        }
        String normalized = normalizeWhitespace(chunk.getChunkText()).toLowerCase(Locale.ROOT);
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private int scoreConceptEvidence(List<String> tokens, ConceptEvidence evidence) {
        int score = 0;
        String concept = evidence.concept().toLowerCase(Locale.ROOT);
        String explanation = evidence.explanation().toLowerCase(Locale.ROOT);

        for (String token : tokens) {
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if (concept.equals(lowerToken)) {
                score += 8;
            } else if (concept.contains(lowerToken)) {
                score += 5;
            }
            if (explanation.contains(lowerToken)) {
                score += 3;
            }
        }

        return score;
    }

    private int scoreSentence(List<String> tokens, String sentence) {
        int score = 0;
        String lowerSentence = sentence.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (lowerSentence.contains(token.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        String joinedTokens = String.join(" ", tokens).toLowerCase(Locale.ROOT);
        if (joinedTokens.contains("search space") && lowerSentence.contains("search space")) {
            score += 4;
        }
        if (joinedTokens.contains("다형성") && lowerSentence.contains("다형성")) {
            score += 4;
        }
        return score;
    }

    private List<String> extractSearchTokens(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalizeWhitespace(query).split("\\s+")) {
            String normalized = normalizeSearchToken(token);
            if (normalized.length() >= 2 && !QUESTION_STOP_WORDS.contains(normalized.toLowerCase(Locale.ROOT))) {
                tokens.add(normalized);
            }
        }
        return new ArrayList<>(tokens);
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeSearchToken(String token) {
        if (token == null) {
            return "";
        }

        String normalized = token.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]", "").trim();
        String[] particles = {
                "으로", "에서", "에게", "한테", "까지", "부터", "처럼",
                "은", "는", "이", "가", "을", "를", "과", "와",
                "만", "도", "에", "로", "의", "야"
        };

        for (String particle : particles) {
            if (normalized.endsWith(particle) && normalized.length() > particle.length() + 1) {
                normalized = normalized.substring(0, normalized.length() - particle.length());
                break;
            }
        }

        return normalized;
    }

    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String abbreviate(String text, int maxLength) {
        String normalized = normalizeWhitespace(text);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private List<RagGeneratedQuestionResponse> buildAdaptiveQuestionSet(
            String rawText,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences,
            String type,
            int count
    ) {
        List<RagGeneratedQuestionResponse> llmQuestions = buildAdaptiveQuestionSetWithOllama(rawText, conceptEvidence, evidenceSentences, type, count);
        if (!llmQuestions.isEmpty()) {
            return llmQuestions;
        }

        List<RagGeneratedQuestionResponse> questions = new ArrayList<>();
        List<ConceptEvidence> pool = conceptEvidence.isEmpty()
                ? evidenceSentences.stream().map(sentence -> new ConceptEvidence(extractLeadingConcept(sentence), sentence)).toList()
                : conceptEvidence.stream().distinct().toList();

        for (int i = 0; i < pool.size() && questions.size() < count; i++) {
            ConceptEvidence evidence = pool.get(i);
            String selectedType = selectQuestionType(type, questions.size());
            if ("multiple_choice".equals(selectedType)) {
                questions.add(buildMultipleChoiceQuestion(questions.size() + 1, evidence, pool));
            } else if ("ox".equals(selectedType)) {
                questions.add(buildOxQuestion(questions.size() + 1, evidence, pool));
            } else {
                questions.add(buildShortAnswerQuestion(questions.size() + 1, evidence));
            }
        }

        while (questions.size() < count) {
            String fallback = evidenceSentences.isEmpty() ? "문서 핵심 개념을 설명하세요." : evidenceSentences.get(Math.min(questions.size(), evidenceSentences.size() - 1));
            String selectedType = selectQuestionType(type, questions.size());
            questions.add(buildFallbackQuestion(questions.size() + 1, selectedType, fallback));
        }

        ensureUnderstandingLevelCoverage(questions, type, count, pool);
        return questions;
    }

    private List<DocumentChunk> selectRepresentativeChunks(List<DocumentChunk> chunks, int limit) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> ranked = chunks.stream()
                .map(chunk -> new ScoredChunk(chunk, scoreRepresentativeChunk(chunk, chunks.size())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();

        return diversifyTopChunks(ranked, Math.min(limit, chunks.size()));
    }

    private double scoreRepresentativeChunk(DocumentChunk chunk, int totalChunks) {
        List<String> evidenceSentences = collectEvidenceSentences(chunk.getChunkText(), List.of(chunk));
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(chunk.getChunkText(), List.of(chunk));

        double explanationDensity = Math.min(1.0, evidenceSentences.size() / 3.0);
        double conceptDensity = Math.min(1.0, conceptEvidence.size() / 2.0);
        double positionBonus = totalChunks <= 1
                ? 0.4
                : Math.max(0.1, 0.5 - ((double) chunk.getChunkIndex() / Math.max(1, totalChunks - 1)) * 0.3);
        double lengthBonus = Math.min(1.0, normalizeWhitespace(chunk.getChunkText()).length() / 260.0);

        return (conceptDensity * 0.4) + (explanationDensity * 0.3) + (positionBonus * 0.2) + (lengthBonus * 0.1);
    }

    private List<RagGeneratedQuestionResponse> buildAdaptiveQuestionSetWithOllama(
            String rawText,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences,
            String type,
            int count
    ) {
        if (!ollamaService.isEnabled()) {
            return List.of();
        }

        String evidenceBlock = conceptEvidence.stream()
                .limit(8)
                .map(item -> "- " + item.concept() + ": " + item.explanation())
                .collect(Collectors.joining("\n"));
        String sentenceBlock = evidenceSentences.stream()
                .limit(8)
                .map(sentence -> "- " + sentence)
                .collect(Collectors.joining("\n"));

        String response = ollamaService.generateJson(
                """
                You create Korean study questions from PDF evidence.
                Return strict JSON only.
                Each item type must be either multiple_choice, short_answer, or ox.
                For ox, question must be a declarative true/false statement, not a "what is" or "explain" question.
                For multiple_choice, include exactly 4 choices and set correctAnswer to the exact correct choice text.
                For short_answer, choices must be an empty array.
                For short_answer, do not create O/X, true/false, yes/no, or judgment-only questions.
                For short_answer, the question must not be answerable with only "O", "X", "맞다", "틀리다", "예", or "아니오".
                For short_answer, the question must require explanation, comparison, reason, relationship, or example-based explanation.
                For short_answer, avoid questions starting with "다음 설명이 맞으면", "맞는가", "옳은가", "참인가", "O/X".
                If a question asks whether a statement is true or false, its type must be ox, not short_answer.
                For ox, choices must be exactly ["O","X"] and correctAnswer must be either O or X.
                understandingLevel must be one of CONCEPT_UNDERSTANDING, CONCEPT_DISTINCTION, CONCEPT_APPLICATION.
                If %d is 3 or more, include at least one question for each understandingLevel.
                conceptTag should be the main concept the question is checking.
                JSON shape:
                {"questions":[{"type":"multiple_choice","question":"...","choices":["..."],"correctAnswer":"...","modelAnswer":"...","explanation":"...","sourceEvidence":"...","difficulty":"easy","conceptTag":"...","understandingLevel":"CONCEPT_DISTINCTION"}]}
                Make exactly %d items.
                Preferred mode: %s
                Do not invent facts outside the evidence.
                """.formatted(count, count, type),
                """
                [Concept Evidence]
                %s

                [Supporting Sentences]
                %s

                [Raw Excerpt]
                %s
                """.formatted(evidenceBlock, sentenceBlock, abbreviate(rawText, 1800))
                ,
                Math.max(900, count * 320)
        );

        if (response == null || response.isBlank()) {
            return List.of();
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(extractJsonObject(response));
            com.fasterxml.jackson.databind.JsonNode questionsNode = root.get("questions");
            if (questionsNode == null || !questionsNode.isArray()) {
                return List.of();
            }

            List<RagGeneratedQuestionResponse> questions = new ArrayList<>();
            int order = 1;
            for (com.fasterxml.jackson.databind.JsonNode node : questionsNode) {
                RagGeneratedQuestionResponse question = toGeneratedQuestion(order++, node);
                question = coerceQuestionType(question, type, conceptEvidence, evidenceSentences);

                if (question != null
                        && "short_answer".equals(question.getType())
                        && isOxLikeQuestion(question.getQuestion())) {
                    continue;
                }


                if (question != null && isValidQuestionType(question.getType(), type)) {
                    questions.add(question);
                }

                if (questions.size() == count) {
                    break;
                }
            }
            fillMissingLlmQuestions(questions, type, count, conceptEvidence, evidenceSentences);
            ensureUnderstandingLevelCoverage(questions, type, count, buildConceptPool(conceptEvidence, evidenceSentences, questions));
            return questions.isEmpty() ? List.of() : questions;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isOxLikeQuestion(String question) {
        String normalized = normalizeWhitespace(question).toLowerCase(Locale.ROOT);

        return normalized.contains("다음 설명이 맞으면")
                || normalized.contains("틀리면 x")
                || normalized.contains("맞으면 o")
                || normalized.contains("o/x")
                || normalized.contains("ox")
                || normalized.contains("맞는가")
                || normalized.contains("옳은가")
                || normalized.contains("참인가")
                || normalized.contains("참 또는 거짓")
                || normalized.contains("true or false")
                || normalized.matches(".*(맞다|틀리다|예|아니오)\\s*(로|으로)?\\s*(답|대답).*");
    }

    private void fillMissingLlmQuestions(
            List<RagGeneratedQuestionResponse> questions,
            String type,
            int count,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences
    ) {
        if (questions.isEmpty()) {
            return;
        }
        List<ConceptEvidence> pool = buildConceptPool(conceptEvidence, evidenceSentences, questions);

        while (questions.size() < count) {
            int index = questions.size();
            String selectedType = selectQuestionType(type, index);
            ConceptEvidence evidence = pool.isEmpty()
                    ? new ConceptEvidence("핵심 개념", questions.get(0).getModelAnswer())
                    : pool.get(index % pool.size());
            if ("multiple_choice".equals(selectedType)) {
                questions.add(buildMultipleChoiceQuestion(index + 1, evidence, pool));
            } else if ("ox".equals(selectedType)) {
                questions.add(buildOxQuestion(index + 1, evidence, pool));
            } else {
                questions.add(buildShortAnswerQuestion(index + 1, evidence));
            }
        }
    }

    private List<ConceptEvidence> buildConceptPool(
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences,
            List<RagGeneratedQuestionResponse> existingQuestions
    ) {
        List<ConceptEvidence> pool = conceptEvidence.isEmpty()
                ? evidenceSentences.stream()
                .map(sentence -> new ConceptEvidence(extractLeadingConcept(sentence), sentence))
                .toList()
                : conceptEvidence.stream().distinct().toList();
        if (!pool.isEmpty()) {
            return pool;
        }
        if (existingQuestions != null && !existingQuestions.isEmpty()) {
            RagGeneratedQuestionResponse question = existingQuestions.get(0);
            return List.of(new ConceptEvidence(question.getConceptTag(), question.getModelAnswer()));
        }
        return List.of(new ConceptEvidence("핵심 개념", "문서의 핵심 개념을 이해하고 구분한 뒤 상황에 적용할 수 있어야 합니다."));
    }

    private void ensureUnderstandingLevelCoverage(
            List<RagGeneratedQuestionResponse> questions,
            String requestedType,
            int count,
            List<ConceptEvidence> pool
    ) {
        if (count < 3) {
            return;
        }
        List<String> requiredLevels = List.of(
                "CONCEPT_UNDERSTANDING",
                "CONCEPT_DISTINCTION",
                "CONCEPT_APPLICATION"
        );

        for (int i = 0; i < requiredLevels.size(); i++) {
            String level = requiredLevels.get(i);
            if (questions.stream().anyMatch(question -> level.equals(question.getUnderstandingLevel()))) {
                continue;
            }

            ConceptEvidence evidence = pool.get(i % pool.size());
            RagGeneratedQuestionResponse coverageQuestion = buildCoverageQuestion(
                    Math.min(i + 1, count),
                    requestedType,
                    level,
                    evidence,
                    pool
            );

            if (questions.size() < count) {
                questions.add(coverageQuestion);
            } else {
                questions.set(findReplaceIndexForCoverage(questions, requiredLevels), coverageQuestion);
            }
        }

        for (int i = 0; i < questions.size(); i++) {
            RagGeneratedQuestionResponse question = questions.get(i);
            if (!Integer.valueOf(i + 1).equals(question.getOrder())) {
                questions.set(i, withOrder(question, i + 1));
            }
        }
    }

    private int findReplaceIndexForCoverage(List<RagGeneratedQuestionResponse> questions, List<String> requiredLevels) {
        for (int i = questions.size() - 1; i >= 0; i--) {
            String level = questions.get(i).getUnderstandingLevel();
            long sameLevelCount = questions.stream()
                    .filter(question -> level.equals(question.getUnderstandingLevel()))
                    .count();
            if (!requiredLevels.contains(level) || sameLevelCount > 1) {
                return i;
            }
        }
        return questions.size() - 1;
    }

    private RagGeneratedQuestionResponse buildCoverageQuestion(
            int order,
            String requestedType,
            String understandingLevel,
            ConceptEvidence evidence,
            List<ConceptEvidence> pool
    ) {
        String selectedType = selectCoverageQuestionType(requestedType, understandingLevel);
        RagGeneratedQuestionResponse base;
        if ("CONCEPT_DISTINCTION".equals(understandingLevel)) {
            base = "multiple_choice".equals(selectedType)
                    ? buildMultipleChoiceQuestion(order, evidence, pool)
                    : buildTypedCoverageQuestion(order, selectedType, evidence, understandingLevel);
        } else if ("CONCEPT_APPLICATION".equals(understandingLevel)) {
            base = buildApplicationQuestion(order, selectedType, evidence, pool);
        } else {
            if ("multiple_choice".equals(selectedType)) {
                base = buildMultipleChoiceQuestion(order, evidence, pool);
            } else if ("ox".equals(selectedType)) {
                base = buildOxQuestion(order, evidence, pool);
            } else {
                base = buildShortAnswerQuestion(order, evidence);
            }
        }
        return withUnderstandingLevel(base, understandingLevel);
    }

    private String selectCoverageQuestionType(String requestedType, String understandingLevel) {
        if (!"mixed".equals(requestedType)) {
            return requestedType;
        }
        if ("CONCEPT_DISTINCTION".equals(understandingLevel)) {
            return "multiple_choice";
        }
        if ("CONCEPT_APPLICATION".equals(understandingLevel)) {
            return "short_answer";
        }
        return "short_answer";
    }

    private RagGeneratedQuestionResponse buildTypedCoverageQuestion(
            int order,
            String selectedType,
            ConceptEvidence evidence,
            String understandingLevel
    ) {
        if ("ox".equals(selectedType)) {
            return new RagGeneratedQuestionResponse(
                    order,
                    "ox",
                    buildOxQuestionText("", evidence.explanation()),
                    List.of("O", "X"),
                    "O",
                    evidence.explanation(),
                    "문서의 설명과 일치하므로 O가 정답입니다.",
                    evidence.explanation(),
                    "medium",
                    evidence.concept(),
                    understandingLevel
            );
        }
        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                evidence.concept() + " 개념을 다른 개념과 구분하는 핵심 기준을 설명하세요.",
                List.of(),
                evidence.explanation(),
                evidence.explanation(),
                "개념 구분 문제는 비슷한 개념 사이에서 이 개념의 특징을 식별하는지 확인합니다.",
                evidence.explanation(),
                "medium",
                evidence.concept(),
                understandingLevel
        );
    }

    private RagGeneratedQuestionResponse buildApplicationQuestion(
            int order,
            String selectedType,
            ConceptEvidence evidence,
            List<ConceptEvidence> pool
    ) {
        if ("multiple_choice".equals(selectedType)) {
            RagGeneratedQuestionResponse base = buildMultipleChoiceQuestion(order, evidence, pool);
            return new RagGeneratedQuestionResponse(
                    order,
                    "multiple_choice",
                    evidence.concept() + " 개념을 실제 상황에 적용한 설명으로 가장 알맞은 것은 무엇인가요?",
                    base.getChoices(),
                    base.getCorrectAnswer(),
                    base.getModelAnswer(),
                    "개념 적용 문제는 정의를 외우는 것이 아니라 상황에서 해당 개념을 사용할 수 있는지 확인합니다.",
                    base.getSourceEvidence(),
                    "medium",
                    evidence.concept(),
                    "CONCEPT_APPLICATION"
            );
        }
        if ("ox".equals(selectedType)) {
            return new RagGeneratedQuestionResponse(
                    order,
                    "ox",
                    buildOxQuestionText("", evidence.explanation()),
                    List.of("O", "X"),
                    "O",
                    evidence.explanation(),
                    "문서 근거를 실제 상황에 연결한 설명이므로 O가 정답입니다.",
                    evidence.explanation(),
                    "medium",
                    evidence.concept(),
                    "CONCEPT_APPLICATION"
            );
        }
        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                evidence.concept() + " 개념을 실제 예시나 상황에 어떻게 적용할 수 있는지 설명하세요.",
                List.of(),
                evidence.explanation(),
                evidence.explanation(),
                "개념 적용 문제는 문서의 설명을 새로운 상황에 연결해 설명할 수 있는지 확인합니다.",
                evidence.explanation(),
                "medium",
                evidence.concept(),
                "CONCEPT_APPLICATION"
        );
    }

    private RagGeneratedQuestionResponse withUnderstandingLevel(RagGeneratedQuestionResponse question, String understandingLevel) {
        return new RagGeneratedQuestionResponse(
                question.getOrder(),
                question.getType(),
                question.getQuestion(),
                question.getChoices(),
                question.getCorrectAnswer(),
                question.getModelAnswer(),
                question.getExplanation(),
                question.getSourceEvidence(),
                question.getDifficulty(),
                question.getConceptTag(),
                understandingLevel
        );
    }

    private RagGeneratedQuestionResponse withOrder(RagGeneratedQuestionResponse question, int order) {
        return new RagGeneratedQuestionResponse(
                order,
                question.getType(),
                question.getQuestion(),
                question.getChoices(),
                question.getCorrectAnswer(),
                question.getModelAnswer(),
                question.getExplanation(),
                question.getSourceEvidence(),
                question.getDifficulty(),
                question.getConceptTag(),
                question.getUnderstandingLevel()
        );
    }

    private RagGeneratedQuestionResponse coerceQuestionType(
            RagGeneratedQuestionResponse question,
            String requestedType,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences
    ) {
        if (question == null || "mixed".equals(requestedType) || requestedType.equals(question.getType())) {
            return question;
        }
        if ("multiple_choice".equals(requestedType)) {
            return toMultipleChoiceFromLlmQuestion(question, conceptEvidence, evidenceSentences);
        }
        if ("ox".equals(requestedType)) {
            String statement = selectOxStatementSource(question);
            return new RagGeneratedQuestionResponse(
                    question.getOrder(),
                    "ox",
                    buildOxQuestionText(question.getQuestion(), statement),
                    List.of("O", "X"),
                    "O",
                    statement,
                    question.getExplanation(),
                    question.getSourceEvidence(),
                    question.getDifficulty(),
                    question.getConceptTag(),
                    "CONCEPT_UNDERSTANDING"
            );
        }
        if ("short_answer".equals(requestedType)) {
            if (isOxLikeQuestion(question.getQuestion())) {
                return null;
            }

            return new RagGeneratedQuestionResponse(
                    question.getOrder(),
                    "short_answer",
                    question.getQuestion(),
                    List.of(),
                    question.getModelAnswer(),
                    question.getModelAnswer(),
                    question.getExplanation(),
                    question.getSourceEvidence(),
                    question.getDifficulty(),
                    question.getConceptTag(),
                    question.getUnderstandingLevel()
            );
        }
        return question;
    }

    private RagGeneratedQuestionResponse toMultipleChoiceFromLlmQuestion(
            RagGeneratedQuestionResponse question,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences
    ) {
        String answer = normalizeWhitespace(question.getCorrectAnswer().isBlank()
                ? question.getModelAnswer()
                : question.getCorrectAnswer());
        List<String> choices = new ArrayList<>();
        choices.add(answer);
        conceptEvidence.stream()
                .map(ConceptEvidence::explanation)
                .map(this::normalizeWhitespace)
                .filter(value -> !value.isBlank() && !value.equals(answer))
                .distinct()
                .limit(3)
                .forEach(choices::add);
        evidenceSentences.stream()
                .map(this::normalizeWhitespace)
                .filter(value -> !value.isBlank() && !value.equals(answer))
                .distinct()
                .limit(3)
                .forEach(value -> {
                    if (choices.size() < 4) {
                        choices.add(value);
                    }
                });
        while (choices.size() < 4) {
            choices.add(buildFallbackDistractor(new ConceptEvidence(question.getConceptTag(), answer), choices.size() - 1));
        }
        Collections.shuffle(choices);
        return new RagGeneratedQuestionResponse(
                question.getOrder(),
                "multiple_choice",
                question.getQuestion(),
                choices.subList(0, 4),
                answer,
                question.getModelAnswer(),
                question.getExplanation(),
                question.getSourceEvidence(),
                question.getDifficulty(),
                question.getConceptTag(),
                "CONCEPT_DISTINCTION"
        );
    }

    private RagGeneratedQuestionResponse toGeneratedQuestion(int order, com.fasterxml.jackson.databind.JsonNode node) {
        String type = normalizeQuestionType(normalizeWhitespace(node.path("type").asText("short_answer")));
        List<String> choices = new ArrayList<>();
        if (node.path("choices").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode choice : node.path("choices")) {
                String value = normalizeWhitespace(choice.asText());
                if (!value.isBlank()) {
                    choices.add(value);
                }
            }
        }


            String question = normalizeWhitespace(node.path("question").asText());
            String correctAnswer = normalizeWhitespace(node.path("correctAnswer").asText());
            String modelAnswer = normalizeWhitespace(node.path("modelAnswer").asText());
            if (modelAnswer.isBlank()) {
                modelAnswer = normalizeWhitespace(node.path("answer").asText());
            }
            String explanation = normalizeWhitespace(node.path("explanation").asText());
            String sourceEvidence = normalizeWhitespace(node.path("sourceEvidence").asText());
            String difficulty = normalizeWhitespace(node.path("difficulty").asText("medium"));
            String conceptTag = normalizeWhitespace(node.path("conceptTag").asText());
            String understandingLevel = normalizeWhitespace(node.path("understandingLevel").asText());

            if (question.isBlank()) {
                return null;
            }
            if (modelAnswer.isBlank()) {
                modelAnswer = !correctAnswer.isBlank() ? correctAnswer : sourceEvidence;
            }
            if (modelAnswer.isBlank()) {
                modelAnswer = explanation;
            }
            if (modelAnswer.isBlank()) {
                modelAnswer = question;
            }
            if (correctAnswer.isBlank()) {
                correctAnswer = modelAnswer;
            }
            if (explanation.isBlank()) {
                explanation = sourceEvidence.isBlank() ? modelAnswer : sourceEvidence;
            }
            if (modelAnswer.isBlank() || correctAnswer.isBlank()) {
                return null;
            }

            if ("multiple_choice".equals(type) && choices.size() != 4) {
                type = "short_answer";
                choices = List.of();
            }
            if ("ox".equals(type)) {
                choices = List.of("O", "X");
                question = buildOxQuestionText(question, selectOxStatementSource(question, modelAnswer, explanation, sourceEvidence));

                String normalizedOxAnswer = normalizeOxAnswer(correctAnswer);
                correctAnswer = normalizedOxAnswer.isBlank() ? "O" : normalizedOxAnswer;
            }


            if ("short_answer".equals(type)) {
                choices = List.of();
            }

        return new RagGeneratedQuestionResponse(
                order,
                type,
                question,
                choices,
                correctAnswer,
                modelAnswer,
                explanation,
                sourceEvidence.isBlank() ? modelAnswer : sourceEvidence,
                difficulty.isBlank() ? "medium" : difficulty,
                conceptTag.isBlank() ? inferConceptTag(question, sourceEvidence, modelAnswer) : conceptTag,
                normalizeUnderstandingLevel(understandingLevel, type, question)
        );
    }

    private String normalizeOxAnswer(String answer) {
        String normalized = normalizeWhitespace(answer).toUpperCase(Locale.ROOT);

        if (normalized.equals("O") || normalized.equals("TRUE") || normalized.equals("맞다") || normalized.equals("참")) {
            return "O";
        }

        if (normalized.equals("X") || normalized.equals("FALSE") || normalized.equals("틀리다") || normalized.equals("거짓")) {
            return "X";
        }

        return "";
    }

    private String extractJsonObject(String response) {
        String trimmed = response == null ? "" : response.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String buildOxQuestionText(String question, String modelAnswer) {
        String statement = normalizeWhitespace(modelAnswer);
        statement = stripLeadingOxMarker(statement);


        if (statement.isBlank()) {
            statement = normalizeWhitespace(question);
        }
        if (isInvalidOxStatement(statement)) {
            statement = normalizeWhitespace(question);
        }
        statement = statement
                .replace("은(는)", "은")
                .replace("이(가)", "이")
                .replace("을(를)", "을")
                .replaceAll("[?？]+$", "")
                .replaceAll("(무엇을 의미합니까|무엇인가요|설명하세요|고르세요)$", "")
                .trim();
        if (isInvalidOxStatement(statement)) {
            statement = "문서의 핵심 개념은 제시된 설명과 일치한다";
        }
        if (!statement.endsWith(".")) {
            statement = statement + ".";
        }
        return "다음 설명이 맞으면 O, 틀리면 X를 고르세요.\n\"" + statement + "\"";
    }

    private String stripLeadingOxMarker(String value) {
        return normalizeWhitespace(value)
            .replaceAll("^(O|X|TRUE|FALSE|참|거짓|맞다|틀리다)\\s*[.)．:]\\s*", "")
            .trim();
}

    private String selectOxStatementSource(RagGeneratedQuestionResponse question) {
        return selectOxStatementSource(
                question.getQuestion(),
                question.getModelAnswer(),
                question.getExplanation(),
                question.getSourceEvidence()
        );
    }

    private String selectOxStatementSource(String question, String modelAnswer, String explanation, String sourceEvidence) {
        List<String> candidates = List.of(modelAnswer, explanation, sourceEvidence, question);
        for (String candidate : candidates) {
            String normalized = normalizeWhitespace(candidate);
            if (!isInvalidOxStatement(normalized)) {
                return normalized;
            }
        }
        return "문서의 핵심 개념은 제시된 설명과 일치한다";
    }

    private boolean isInvalidOxStatement(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.isBlank()) {
            return true;
        }
        String compact = normalized.replace(".", "").trim();
        return compact.equalsIgnoreCase("O")
                || compact.equalsIgnoreCase("X")
                || compact.equalsIgnoreCase("true")
                || compact.equalsIgnoreCase("false")
                || compact.equals("정답")
                || compact.equals("오답")
                || compact.length() < 6;
    }


    private String normalizeQuestionType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "multiple_choice", "multiple choice", "choice", "mcq", "객관식" -> "multiple_choice";
            case "ox", "o/x", "true_false", "true/false", "참거짓", "ox퀴즈" -> "ox";
            default -> "short_answer";
        };
    }

    private RagGeneratedQuestionResponse buildShortAnswerQuestion(int order, ConceptEvidence evidence) {
        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                evidence.concept() + "의 의미를 설명하고, 문서에서 제시된 특징이나 예시와 연결해서 서술하세요.",
                List.of(),
                evidence.explanation(),
                evidence.explanation(),
                evidence.concept() + "의 정의뿐 아니라 역할, 특징, 예시 중 하나 이상을 함께 설명해야 합니다.",
                evidence.explanation(),
                "medium",
                evidence.concept(),
                "CONCEPT_UNDERSTANDING"
        );
    }

    private RagGeneratedQuestionResponse buildOxQuestion(int order, ConceptEvidence evidence, List<ConceptEvidence> pool) {
        ConceptEvidence distractor = pool.stream()
                .filter(candidate -> !candidate.concept().equals(evidence.concept()))
                .findFirst()
                .orElse(null);
        boolean buildFalseStatement = distractor != null && order % 2 == 0;
        String statement = buildFalseStatement
                ? evidence.concept() + "의 설명은 다음과 같다: " + distractor.explanation()
                : evidence.explanation();
        String correctAnswer = buildFalseStatement ? "X" : "O";
        String explanation = buildFalseStatement
                ? "문서 근거에서 " + evidence.concept() + " 개념은 다음과 같이 설명됩니다: " + evidence.explanation()
                : "문서에서 " + evidence.concept() + "에 대한 설명과 일치하므로 O가 정답입니다.";

        return new RagGeneratedQuestionResponse(
                order,
                "ox",
                "다음 설명이 맞으면 O, 틀리면 X를 고르세요.\n\"" + statement + "\"",
                List.of("O", "X"),
                correctAnswer,
                evidence.explanation(),
                explanation,
                evidence.explanation(),
                "easy",
                evidence.concept(),
                "CONCEPT_UNDERSTANDING"
        );
    }

    private RagGeneratedQuestionResponse buildMultipleChoiceQuestion(int order, ConceptEvidence target, List<ConceptEvidence> pool) {
        List<String> distractors = pool.stream()
                .filter(candidate -> !candidate.concept().equals(target.concept()))
                .map(ConceptEvidence::explanation)
                .filter(explanation -> !explanation.equals(target.explanation()))
                .distinct()
                .limit(3)
                .collect(Collectors.toCollection(ArrayList::new));

        while (distractors.size() < 3) {
            distractors.add(buildFallbackDistractor(target, distractors.size()));
        }

        List<String> choices = new ArrayList<>();
        choices.add(target.explanation());
        choices.addAll(distractors.subList(0, 3));
        Collections.shuffle(choices);

        return new RagGeneratedQuestionResponse(
                order,
                "multiple_choice",
                target.concept() + "에 대한 설명으로 가장 알맞은 것은 무엇인가요?",
                choices,
                target.explanation(),
                target.explanation(),
                "정답은 문서에서 " + target.concept() + " 개념을 직접 설명한 문장입니다.",
                target.explanation(),
                "medium",
                target.concept(),
                "CONCEPT_DISTINCTION"
        );
    }

    private RagGeneratedQuestionResponse buildFallbackQuestion(int order, String selectedType, String fallback) {
        if ("multiple_choice".equals(selectedType)) {
            List<String> choices = new ArrayList<>(List.of(
                    fallback,
                    "문서 내용과 직접 일치하지 않는 설명입니다.",
                    "문서에서 중요하지 않다고 한 내용입니다.",
                    "근거 없이 일반화한 설명입니다."
            ));
            Collections.shuffle(choices);
            return new RagGeneratedQuestionResponse(
                    order,
                    "multiple_choice",
                    "문서 내용과 가장 일치하는 설명을 고르세요.",
                    choices,
                    fallback,
                    fallback,
                    "문서 근거와 가장 직접적으로 일치하는 설명이 정답입니다.",
                    fallback,
                    "easy",
                    inferConceptTag(fallback, fallback, fallback),
                    "CONCEPT_DISTINCTION"
            );
        }

        if ("ox".equals(selectedType)) {
            boolean isTrue = order % 2 != 0;
            String statement = isTrue
                    ? fallback
                    : fallback + " 따라서 이 개념은 문서에서 중요하지 않다고 결론낸다.";
            return new RagGeneratedQuestionResponse(
                    order,
                    "ox",
                    "다음 설명이 맞으면 O, 틀리면 X를 고르세요.\n\"" + statement + "\"",
                    List.of("O", "X"),
                    isTrue ? "O" : "X",
                    fallback,
                    isTrue
                            ? "문서 내용과 일치하므로 O가 정답입니다."
                            : "뒤 문장은 문서 근거와 일치하지 않으므로 X가 정답입니다.",
                    fallback,
                    "easy",
                    inferConceptTag(fallback, fallback, fallback),
                    "CONCEPT_UNDERSTANDING"
            );
        }

        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                "문서에서 중요하다고 생각하는 내용을 설명하세요.",
                List.of(),
                fallback,
                fallback,
                "핵심 내용을 자신의 말로 다시 설명하는 연습용 문제입니다.",
                fallback,
                "easy",
                inferConceptTag(fallback, fallback, fallback),
                "CONCEPT_APPLICATION"
        );
    }

    private String inferConceptTag(String question, String sourceEvidence, String modelAnswer) {
        String[] candidates = {question, sourceEvidence, modelAnswer};
        for (String candidate : candidates) {
            String extracted = extractLeadingConcept(normalizeWhitespace(candidate));
            if (extracted != null && !extracted.isBlank() && !"문서".equals(extracted)) {
                return extracted;
            }
        }
        return "핵심 개념";
    }

    private String normalizeUnderstandingLevel(String value, String type, String question) {
        if ("CONCEPT_UNDERSTANDING".equals(value)
                || "CONCEPT_DISTINCTION".equals(value)
                || "CONCEPT_APPLICATION".equals(value)) {
            return value;
        }

        String normalizedQuestion = normalizeWhitespace(question);
        if (normalizedQuestion.contains("상황") || normalizedQuestion.contains("사례") || normalizedQuestion.contains("예시")) {
            return "CONCEPT_APPLICATION";
        }
        if ("multiple_choice".equals(type)) {
            return "CONCEPT_DISTINCTION";
        }
        if (normalizedQuestion.contains("무엇") || normalizedQuestion.contains("설명")) {
            return "CONCEPT_UNDERSTANDING";
        }
        return "CONCEPT_APPLICATION";
    }

    private String buildFallbackDistractor(ConceptEvidence target, int index) {
        return switch (index % 3) {
            case 0 -> target.concept() + " 개념은 문서에서 다루지 않는 주변 개념이라고 설명한다.";
            case 1 -> target.concept() + "의 핵심은 구현이 아니라 결과만 외우는 것이라고 설명한다.";
            default -> target.concept() + " 개념은 다른 개념과 구분되지 않는다고 설명한다.";
        };
    }

    private String selectQuestionType(String requestedType, int index) {
        if ("multiple_choice".equals(requestedType)) {
            return "multiple_choice";
        }
        if ("ox".equals(requestedType)) {
            return "ox";
        }
        if ("short_answer".equals(requestedType)) {
            return "short_answer";
        }
        return switch (index % 3) {
            case 0 -> "multiple_choice";
            case 1 -> "ox";
            default -> "short_answer";
        };
    }

    private boolean isValidQuestionType(String actualType, String requestedType) {
        if ("mixed".equals(requestedType)) {
            return "multiple_choice".equals(actualType) || "short_answer".equals(actualType) || "ox".equals(actualType);
        }
        return requestedType.equals(actualType);
    }

    private String normalizeGenerationType(String type) {
        String normalized = type == null ? "mixed" : type.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_GENERATION_TYPES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be one of mixed, multiple_choice, short_answer, ox");
        }
        return normalized;
    }

    private int normalizeCount(Integer count) {
        int normalized = count == null ? 3 : count;
        if (normalized < 1 || normalized > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 10");
        }
        return normalized;
    }

    private String extractLeadingConcept(String sentence) {
        Matcher matcher = CONCEPT_WITH_ENGLISH.matcher(sentence);
        if (matcher.find()) {
            return normalizeConcept(matcher.group(1));
        }
        String[] words = normalizeWhitespace(sentence).split("\\s+");
        return words.length == 0 ? "핵심 개념" : words[0];
    }

    private String extractTextFromBytes(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private record ScoredChunk(DocumentChunk chunk, double score) {
    }

    private record ConceptEvidence(String concept, String explanation) {
    }

    private record InlineConcept(String concept, String explanation) {
    }
}
