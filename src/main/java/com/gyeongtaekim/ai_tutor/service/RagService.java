package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.ChatSession;
import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.domain.SessionQuiz;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionsResponse;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.repository.DocumentChunkRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.ChatSessionRepository;
import com.gyeongtaekim.ai_tutor.repository.RagDocumentRepository;
import com.gyeongtaekim.ai_tutor.repository.SessionQuizRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.Optional;
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
    private final ChatSessionDocumentRepository chatSessionDocumentRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final QdrantVectorStoreService qdrantVectorStoreService;
    private final SessionQuizRepository sessionQuizRepository;
    private final OllamaService ollamaService;
    private final PdfVisualAnalysisService pdfVisualAnalysisService;

    private static final List<String> ALLOWED_GENERATION_TYPES = List.of("mixed", "multiple_choice", "short_answer", "ox");

    public RagDocumentUploadResponse processPdf(
            MultipartFile file,
            String subject,
            String unitName,
            String trustLevel
    ) throws IOException {
        return processPdf(null, file, subject, unitName, trustLevel);
    }

    public RagDocumentUploadResponse processPdf(
            User user,
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
                user,
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
                    + ", chunkIndex=" + i
                    + ", chunkType=TEXT"
                    + ", sourceType=PDF_TEXT";
            chunks.add(new DocumentChunk(ragDocument, i, segments.get(i).text(), metadata));
        }
        addVisualChunks(file.getBytes(), ragDocument, chunks);
        documentChunkRepository.saveAll(chunks);
        storeChunkEmbeddings(chunks);

        DocumentUploadAnalysis analysis = analyzeUploadedDocument(ragDocument, chunks, text);
        return new RagDocumentUploadResponse(
                ragDocument,
                chunks.size(),
                analysis.inferredSubject(),
                analysis.inferredUnit(),
                analysis.recommendedTags(),
                analysis.documentDifficulty(),
                analysis.confidence()
        );
    }

    public RagDocumentUploadResponse analyzePdfPreview(
            MultipartFile file,
            String subject,
            String unitName,
            String trustLevel
    ) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        String text = extractTextFromBytes(file.getBytes());
        List<TextSegment> segments = splitIntoSegments(text);
        String originalFileName = defaultValue(file.getOriginalFilename(), "preview.pdf");
        RagDocument previewDocument = new RagDocument(
                originalFileName,
                RagDocument.SourceType.PDF,
                defaultValue(trustLevel, "internal"),
                defaultValue(subject, "general"),
                defaultValue(unitName, "general"),
                originalFileName,
                text
        );

        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            chunks.add(new DocumentChunk(
                    previewDocument,
                    i,
                    segments.get(i).text(),
                    "source=" + originalFileName + ", chunkIndex=" + i + ", preview=true, chunkType=TEXT, sourceType=PDF_TEXT"
            ));
        }

        DocumentUploadAnalysis analysis = analyzeUploadedDocument(previewDocument, chunks, text);
        return new RagDocumentUploadResponse(
                previewDocument,
                chunks.size(),
                analysis.inferredSubject(),
                analysis.inferredUnit(),
                analysis.recommendedTags(),
                analysis.documentDifficulty(),
                analysis.confidence()
        );
    }

    private void addVisualChunks(byte[] pdfBytes, RagDocument ragDocument, List<DocumentChunk> chunks) {
        List<PdfVisualAnalysisService.VisualAnalysisResult> visualResults = pdfVisualAnalysisService.analyze(pdfBytes);
        for (PdfVisualAnalysisService.VisualAnalysisResult visualResult : visualResults) {
            int chunkIndex = chunks.size();
            chunks.add(new DocumentChunk(
                    ragDocument,
                    chunkIndex,
                    visualResult.toChunkText(),
                    buildVisualChunkMetadata(ragDocument, visualResult, chunkIndex)
            ));
        }
    }

    private String buildVisualChunkMetadata(
            RagDocument ragDocument,
            PdfVisualAnalysisService.VisualAnalysisResult visualResult,
            int chunkIndex
    ) {
        return "subject=" + ragDocument.getSubject()
                + ", unit=" + ragDocument.getUnitName()
                + ", source=" + ragDocument.getStoredFileName()
                + ", chunkIndex=" + chunkIndex
                + ", chunkType=" + visualResult.chunkType()
                + ", pageNumber=" + visualResult.pageNumber()
                + ", sourceType=GPT_VISION"
                + ", confidence=" + visualResult.confidence()
                + ", title=" + sanitizeMetadataValue(visualResult.title());
    }

    private String sanitizeMetadataValue(String value) {
        return value == null ? "" : value.replace(",", " ").replace("\n", " ").replace("\r", " ").trim();
    }

    public RagQueryResponse query(String query) {
        return query(null, query);
    }

    public RagQueryResponse query(User user, String query) {
        return query(user, query, (Long) null);
    }


    public RagQueryResponse query(String query, List<Long> documentIds) {
        return query(null, query, documentIds);
    }

    public RagQueryResponse query(User user, String query, List<Long> documentIds) {
        if (documentIds == null) {
            return query(user, query, (Long) null);
        }

        List<Long> selectedDocumentIds = documentIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (selectedDocumentIds.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "No selected PDF documents are available for this chat session.",
                    List.of()
            );
        }
        requireAccessibleDocuments(user, selectedDocumentIds);

        List<DocumentChunk> allChunks = selectedDocumentIds.stream()
                .flatMap(documentId -> findChunksForDocument(user, documentId).stream())
                .toList();

        if (allChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "선택된 PDF에서 검색 가능한 문서 근거를 찾지 못했습니다.",
                    List.of()
            );
        }

        List<DocumentChunk> topChunks = retrieveRelevantChunks(user, query, allChunks, null, selectedDocumentIds);
        if (topChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "선택된 PDF에서 질문과 직접 관련된 근거를 찾지 못했습니다.",
                    List.of()
            );
        }

        List<String> evidence = buildAnswerEvidence(query, topChunks);
        List<String> sources = topChunks.stream()
                .map(this::formatChunkSource)
                .distinct()
                .toList();

        String answer = evidence.isEmpty()
                ? topChunks.stream()
                        .map(DocumentChunk::getChunkText)
                        .collect(Collectors.joining("\n\n"))
                : String.join("\n", evidence);

        return new RagQueryResponse(query, answer, sources);
    }

    public RagDocumentUploadResponse analyzeDocument(Long documentId) {
        return analyzeDocument(null, documentId);
    }

    public RagDocumentUploadResponse analyzeDocument(User user, Long documentId) {
        RagDocument document = getDocument(user, documentId);
        List<DocumentChunk> chunks = findChunksForDocument(user, documentId);
        DocumentUploadAnalysis analysis = analyzeUploadedDocument(document, chunks, document.getExtractedText());
        return new RagDocumentUploadResponse(
                document,
                chunks.size(),
                analysis.inferredSubject(),
                analysis.inferredUnit(),
                analysis.recommendedTags(),
                analysis.documentDifficulty(),
                analysis.confidence()
        );
    }

    public RagQueryResponse query(String query, Long documentId) {
        return query(null, query, documentId);
    }

    public RagQueryResponse query(User user, String query, Long documentId) {
        if (documentId != null) {
            getDocument(user, documentId);
        }

        List<DocumentChunk> allChunks = documentId == null
                ? findAllChunksForUser(user)
                : findChunksForDocument(user, documentId);
        if (allChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "검색 가능한 문서가 없습니다. 먼저 /api/rag/upload 로 PDF를 업로드해 주세요.",
                    List.of()
            );
        }

        List<DocumentChunk> topChunks = retrieveRelevantChunks(user, query, allChunks, documentId, List.of());
        if (topChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "질문과 직접 관련된 문서 근거를 찾지 못했습니다. 질문을 더 구체적으로 하거나 관련 PDF를 먼저 업로드해 주세요.",
                    List.of()
            );
        }

        List<String> evidence = buildAnswerEvidence(query, topChunks);
        List<String> sources = topChunks.stream()
                .map(this::formatChunkSource)
                .distinct()
                .toList();

        String answer = evidence.isEmpty()
                ? topChunks.stream().map(DocumentChunk::getChunkText).collect(Collectors.joining("\n\n"))
                : String.join("\n", evidence);

        return new RagQueryResponse(query, answer, sources);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String fileName) {
        return generateQuestions(null, documentId, fileName);
    }

    public RagGeneratedQuestionsResponse generateQuestions(User user, Long documentId, String fileName) {
        if (documentId != null) {
            return generateQuestions(user, documentId);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId or fileName is required");
        }

        RagDocument document = findByStoredFileName(user, fileName)
                .orElseGet(() -> loadLegacyDocument(user, fileName));

        return buildGeneratedQuestionsResponse(document);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String fileName, String type, Integer count) {
        return generateQuestions(null, documentId, List.of(), null, fileName, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(
            Long documentId,
            List<Long> documentIds,
            Long sessionId,
            String fileName,
            String type,
            Integer count
    ) {
        return generateQuestions(null, documentId, documentIds, sessionId, fileName, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(
            User user,
            Long documentId,
            List<Long> documentIds,
            Long sessionId,
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
            return generateQuestions(user, selectedDocumentIds, sessionId, normalizedType, normalizedCount);
        }
        if (documentId != null) {
            return generateQuestions(user, documentId, sessionId, normalizedType, normalizedCount);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId or fileName is required");
        }

        RagDocument document = findByStoredFileName(user, fileName)
                .orElseGet(() -> loadLegacyDocument(user, fileName));

        return buildGeneratedQuestionsResponse(document, normalizedType, normalizedCount, Set.of());
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId) {
        return generateQuestions(null, documentId);
    }

    public RagGeneratedQuestionsResponse generateQuestions(User user, Long documentId) {
        RagDocument document = getDocument(user, documentId);

        return buildGeneratedQuestionsResponse(document);
    }

    public List<RagDocumentSummaryResponse> getDocuments() {
        return getDocuments(null);
    }

    public List<RagDocumentSummaryResponse> getDocuments(User user) {
        List<RagDocument> documents = user == null
                ? ragDocumentRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                : ragDocumentRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return documents.stream()
                .map(RagDocumentSummaryResponse::new)
                .toList();
    }

    public RagDocument getDocument(Long documentId) {
        return getDocument(null, documentId);
    }

    public RagDocument getDocument(User user, Long documentId) {
        if (user != null) {
            return ragDocumentRepository.findByIdAndUserId(documentId, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        }
        return ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    private List<RagDocument> findAccessibleDocuments(User user, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }

        List<Long> normalizedDocumentIds = documentIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedDocumentIds.isEmpty()) {
            return List.of();
        }

        List<RagDocument> documents = ragDocumentRepository.findAllById(normalizedDocumentIds);
        if (user == null) {
            return documents;
        }

        return documents.stream()
                .filter(document -> document.getUser() != null && user.getId().equals(document.getUser().getId()))
                .toList();
    }

    private void requireAccessibleDocuments(User user, List<Long> documentIds) {
        if (user == null || documentIds == null || documentIds.isEmpty()) {
            return;
        }

        Set<Long> requestedIds = documentIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> accessibleIds = findAccessibleDocuments(user, List.copyOf(requestedIds)).stream()
                .map(RagDocument::getId)
                .collect(Collectors.toSet());

        if (!accessibleIds.containsAll(requestedIds)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
    }

    private Optional<RagDocument> findByStoredFileName(User user, String fileName) {
        if (user == null) {
            return ragDocumentRepository.findByStoredFileName(fileName);
        }
        return ragDocumentRepository.findByStoredFileNameAndUserId(fileName, user.getId());
    }

    private List<DocumentChunk> findChunksForDocument(User user, Long documentId) {
        if (user == null) {
            return documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        }
        return documentChunkRepository.findByDocumentIdAndDocumentUserIdOrderByChunkIndexAsc(documentId, user.getId());
    }

    private List<DocumentChunk> findAllChunksForUser(User user) {
        if (user == null) {
            return documentChunkRepository.findAll();
        }
        return documentChunkRepository.findByDocumentUserId(user.getId());
    }

    private void requireSessionDocuments(User user, Long sessionId, List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }

        requireAccessibleDocuments(user, documentIds);

        if (sessionId == null) {
            return;
        }

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat session not found"));
        if (user != null && !user.getId().equals(session.getUser().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chat session does not belong to authenticated user");
        }

        Set<Long> attachedDocumentIds = chatSessionDocumentRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(sessionDocument -> sessionDocument.getDocumentId())
                .collect(Collectors.toSet());
        if (!attachedDocumentIds.containsAll(documentIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected document is not attached to this chat session");
        }
    }

    public Path resolveStoredFilePath(Long documentId) {
        return resolveStoredFilePath(null, documentId);
    }

    public Path resolveStoredFilePath(User user, Long documentId) {
        RagDocument document = getDocument(user, documentId);
        Path path = Paths.get(uploadPath).resolve(document.getStoredFileName()).normalize();
        if (!Files.exists(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file not found");
        }
        return path;
    }

    public RagDocumentSummaryResponse renameDocument(Long documentId, String title) {
        return renameDocument(null, documentId, title);
    }

    public RagDocumentSummaryResponse renameDocument(User user, Long documentId, String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title is required");
        }

        RagDocument document = getDocument(user, documentId);
        document.updateTitle(title.trim());
        return new RagDocumentSummaryResponse(ragDocumentRepository.save(document));
    }


    @Transactional
    public RagDocumentSummaryResponse updateDocumentMetadata(
            Long documentId,
            String subject,
            String unitName,
            String trustLevel
    ) {
        return updateDocumentMetadata(null, documentId, subject, unitName, trustLevel);
    }

    @Transactional
    public RagDocumentSummaryResponse updateDocumentMetadata(
            User user,
            Long documentId,
            String subject,
            String unitName,
            String trustLevel
    ) {
        RagDocument document = getDocument(user, documentId);

        document.updateMetadata(
                defaultValue(subject, document.getSubject()),
                defaultValue(unitName, document.getUnitName()),
                defaultValue(trustLevel, document.getTrustLevel())
        );

        return new RagDocumentSummaryResponse(ragDocumentRepository.save(document));
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        deleteDocument(null, documentId);
    }

    @Transactional
    public void deleteDocument(User user, Long documentId) {
        RagDocument document = getDocument(user, documentId);
        Path path = Paths.get(uploadPath).resolve(document.getStoredFileName()).normalize();
        if (embeddingEnabled()) {
            qdrantVectorStoreService.deleteByDocumentId(documentId);
        }
        documentChunkRepository.deleteAll(findChunksForDocument(user, documentId));
        chatSessionDocumentRepository.deleteAllByDocumentId(documentId);
        ragDocumentRepository.delete(document);
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete stored file");
        }
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, String type, Integer count) {
        return generateQuestions(null, documentId, (Long) null, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(Long documentId, Long sessionId, String type, Integer count) {
        return generateQuestions(null, documentId, sessionId, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(User user, Long documentId, Long sessionId, String type, Integer count) {
        RagDocument document = getDocument(user, documentId);
        requireSessionDocuments(user, sessionId, List.of(documentId));

        return buildGeneratedQuestionsResponse(document, type, count, buildExcludedQuestionFingerprints(sessionId, List.of(documentId)));
    }

    public RagGeneratedQuestionsResponse generateQuestions(List<Long> documentIds, String type, Integer count) {
        return generateQuestions(null, documentIds, null, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(List<Long> documentIds, Long sessionId, String type, Integer count) {
        return generateQuestions(null, documentIds, sessionId, type, count);
    }

    public RagGeneratedQuestionsResponse generateQuestions(User user, List<Long> documentIds, Long sessionId, String type, Integer count) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentIds are required");
        }

        List<Long> normalizedDocumentIds = documentIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedDocumentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentIds are required");
        }
        requireSessionDocuments(user, sessionId, normalizedDocumentIds);

        Map<Long, RagDocument> documentsById = findAccessibleDocuments(user, normalizedDocumentIds).stream()
                .collect(Collectors.toMap(RagDocument::getId, document -> document));
        List<RagDocument> documents = normalizedDocumentIds.stream()
                .map(documentId -> {
                    RagDocument document = documentsById.get(documentId);
                    if (document == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId);
                    }
                    return document;
                })
                .toList();
        List<DocumentChunk> chunks = documents.stream()
                .flatMap(document -> findChunksForDocument(user, document.getId()).stream())
                .toList();

        return buildGeneratedQuestionsResponse(documents, chunks, type, count, buildExcludedQuestionFingerprints(sessionId, normalizedDocumentIds));
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
        return buildGeneratedQuestionsResponse(document, type, count, Set.of());
    }

    private RagGeneratedQuestionsResponse buildGeneratedQuestionsResponse(RagDocument document, String type, int count, Set<String> excludedQuestionFingerprints) {
        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        return buildGeneratedQuestionsResponse(List.of(document), chunks, type, count, excludedQuestionFingerprints);
    }

    private RagGeneratedQuestionsResponse buildGeneratedQuestionsResponse(
            List<RagDocument> documents,
            List<DocumentChunk> chunks,
            String type,
            int count,
            Set<String> excludedQuestionFingerprints
    ) {
        List<DocumentChunk> representativeChunks = selectRepresentativeChunks(chunks, Math.max(count + 2, 4));
        String representativeText = representativeChunks.stream()
                .map(DocumentChunk::getChunkText)
                .collect(Collectors.joining("\n"));
        List<ConceptEvidence> conceptEvidence = extractConceptEvidence(representativeText, representativeChunks);
        List<String> evidenceSentences = collectEvidenceSentences(representativeText, representativeChunks);
        List<RagGeneratedQuestionResponse> questions = buildAdaptiveQuestionSet(
                representativeText,
                conceptEvidence,
                evidenceSentences,
                type,
                count,
                excludedQuestionFingerprints
        );
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
                - The question and modelAnswer must focus on the same concept.
                - Do not copy truncated fragments from evidence.
                - Do not create answers ending with "다음과 같음", "주요 역할은", "아래와 같음".
                - For application questions, include a concrete situation or example and how the concept is used there.
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
        if (!concept.contains(" ")) {
            concept = normalizeSearchToken(concept);
        }
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
        return retrieveRelevantChunks(null, query, allChunks, null, List.of());
    }

    private List<DocumentChunk> retrieveRelevantChunks(String query, List<DocumentChunk> allChunks, Long documentId, List<Long> documentIds) {
        return retrieveRelevantChunks(null, query, allChunks, documentId, documentIds);
    }

    private List<DocumentChunk> retrieveRelevantChunks(User user, String query, List<DocumentChunk> allChunks, Long documentId, List<Long> documentIds) {
        List<String> tokens = extractSearchTokens(query);
        Map<Long, Double> embeddingScores = retrieveWithEmbeddings(user, query, allChunks, documentId, documentIds);
        List<DocumentChunk> candidateChunks = embeddingScores.isEmpty()
                ? allChunks
                : loadChunksByIdsPreservingOrder(embeddingScores.keySet()).stream()
                        .filter(chunk -> isAllowedRetrievedChunk(user, chunk, documentId, documentIds))
                        .toList();

        List<ScoredChunk> ranked = candidateChunks.stream()
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

    private Map<Long, Double> retrieveWithEmbeddings(String query, List<DocumentChunk> candidateChunks, Long documentId, List<Long> documentIds) {
        return retrieveWithEmbeddings(null, query, candidateChunks, documentId, documentIds);
    }

    private Map<Long, Double> retrieveWithEmbeddings(User user, String query, List<DocumentChunk> candidateChunks, Long documentId, List<Long> documentIds) {
        if (!embeddingEnabled()) {
            return Map.of();
        }

        try {
            Set<Long> candidateIds = candidateChunks.stream()
                    .map(DocumentChunk::getId)
                    .collect(Collectors.toSet());
            EmbeddingModel embeddingModel = createEmbeddingModel();
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            return qdrantVectorStoreService
                    .searchSimilarChunks(queryEmbedding.vector(), user == null ? null : user.getId(), documentId, documentIds, EMBEDDING_SEARCH_WINDOW)
                    .stream()
                    .filter(match -> candidateIds.contains(match.chunkId()))
                    .collect(Collectors.toMap(
                            QdrantVectorStoreService.VectorChunkMatch::chunkId,
                            QdrantVectorStoreService.VectorChunkMatch::score,
                            Math::max,
                            LinkedHashMap::new
                    ));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private void storeChunkEmbeddings(List<DocumentChunk> chunks) {
        if (chunks.isEmpty() || !embeddingEnabled()) {
            return;
        }

        try {
            EmbeddingModel embeddingModel = createEmbeddingModel();
            List<TextSegment> segments = chunks.stream()
                    .map(chunk -> TextSegment.from(chunk.getChunkText()))
                    .toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            upsertEmbeddingsToQdrant(chunks, embeddings);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception ignored) {
            // Embedding creation is optional when OpenAI is not configured correctly.
        }
    }

    private void upsertEmbeddingsToQdrant(List<DocumentChunk> chunks, List<Embedding> embeddings) {
        for (int i = 0; i < chunks.size() && i < embeddings.size(); i++) {
            qdrantVectorStoreService.upsertChunkEmbedding(chunks.get(i), embeddings.get(i).vector());
        }
    }

    private List<DocumentChunk> loadChunksByIdsPreservingOrder(Set<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        Map<Long, DocumentChunk> chunksById = documentChunkRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(
                        DocumentChunk::getId,
                        chunk -> chunk,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return chunkIds.stream()
                .map(chunksById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean isAllowedRetrievedChunk(User user, DocumentChunk chunk, Long documentId, List<Long> documentIds) {
        if (chunk == null || chunk.getDocument() == null) {
            return false;
        }
        RagDocument document = chunk.getDocument();
        if (user != null && (document.getUser() == null || !user.getId().equals(document.getUser().getId()))) {
            return false;
        }
        Set<Long> selectedDocumentIds = new LinkedHashSet<>();
        if (documentIds != null) {
            selectedDocumentIds.addAll(documentIds.stream().filter(java.util.Objects::nonNull).toList());
        }
        if (documentId != null) {
            selectedDocumentIds.add(documentId);
        }
        return selectedDocumentIds.isEmpty() || selectedDocumentIds.contains(document.getId());
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

    private RagDocument loadLegacyDocument(User user, String fileName) {
        try {
            Path filePath = Paths.get(uploadPath, fileName);
            if (!Files.exists(filePath)) {
                throw new IOException("File not found: " + fileName);
            }

            byte[] bytes = Files.readAllBytes(filePath);
            String text = extractTextFromBytes(bytes);
            RagDocument ragDocument = ragDocumentRepository.save(new RagDocument(
                    user,
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
                chunks.add(new DocumentChunk(ragDocument, i, segments.get(i).text(), "source=" + fileName + ", chunkIndex=" + i + ", chunkType=TEXT, sourceType=PDF_TEXT"));
            }
            documentChunkRepository.saveAll(chunks);
            storeChunkEmbeddings(chunks);
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

    private DocumentUploadAnalysis analyzeUploadedDocument(RagDocument document, List<DocumentChunk> chunks, String text) {
        String normalizedText = normalizeWhitespace(text);
        List<DocumentChunk> representativeChunks = selectAnalysisRepresentativeChunks(chunks, 9);
        String representativeText = representativeChunks.stream()
                .map(DocumentChunk::getChunkText)
                .collect(Collectors.joining("\n"));
        String analysisText = normalizeWhitespace(document.getTitle() + " " + representativeText + " " + normalizedText);

        Map<String, Integer> conceptScores = scoreComputerScienceConcepts(analysisText);
        String inferredSubject = inferUploadedDocumentSubject(document, analysisText, conceptScores);
        String inferredUnit = inferUploadedDocumentUnit(inferredSubject, conceptScores);
        List<String> recommendedTags = buildUploadRecommendedTags(inferredSubject, inferredUnit, conceptScores);
        String documentDifficulty = inferUploadDocumentDifficulty(chunks, normalizedText, conceptScores);
        String confidence = defaultValue(document.getTrustLevel(), "보통");

        DocumentUploadAnalysis ruleBasedAnalysis = new DocumentUploadAnalysis(
                inferredSubject,
                inferredUnit,
                recommendedTags,
                documentDifficulty,
                confidence
        );

        DocumentUploadAnalysis llmAnalysis = refineUploadAnalysisWithLocalLlm(
                document,
                representativeText,
                analysisText,
                conceptScores,
                ruleBasedAnalysis
        );
        if (llmAnalysis != null) {
            return llmAnalysis;
        }

        return new DocumentUploadAnalysis(
                inferredSubject,
                inferredUnit,
                recommendedTags,
                documentDifficulty,
                confidence
        );
    }

    private DocumentUploadAnalysis refineUploadAnalysisWithLocalLlm(
            RagDocument document,
            String representativeText,
            String analysisText,
            Map<String, Integer> conceptScores,
            DocumentUploadAnalysis fallback
    ) {
        if (!ollamaService.isEnabled()) {
            return null;
        }

        String conceptScoreText = conceptScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        String allowedSubjects = String.join(", ", buildComputerScienceSubjectKeywords().keySet());
        String response = ollamaService.generateJson(
                """
                You classify Korean computer-science study PDFs.
                Return strict JSON only.
                Do not use markdown.
                Do not invent a topic that is not supported by the evidence.
                Prefer the main repeated learning topic over cover pages, table of contents, file names, page numbers, or generic words.
                If the PDF is about data analysis tools such as Pandas, DataFrame, Series, NumPy, preprocessing, or visualization, classify it as 데이터분석.
                If the PDF is about RAG, embeddings, pgvector, GPT Vision, vector search, or local LLM systems, classify it as RAG 시스템.
                """,
                """
                [Allowed subjects]
                %s

                [File title]
                %s

                [Rule-based guess]
                subject=%s
                unit=%s
                tags=%s
                difficulty=%s

                [Keyword scores]
                %s

                [Representative PDF evidence]
                %s

                [Whole-text excerpt]
                %s

                Return JSON:
                {"subject":"one allowed subject","unit":"specific chapter/unit, 2-30 Korean chars","tags":["3-8 concise tags"],"difficulty":"쉬움|보통|어려움","confidence":"낮음|보통|높음"}
                """.formatted(
                        allowedSubjects,
                        document.getTitle(),
                        fallback.inferredSubject(),
                        fallback.inferredUnit(),
                        fallback.recommendedTags(),
                        fallback.documentDifficulty(),
                        conceptScoreText.isBlank() ? "none" : conceptScoreText,
                        abbreviate(representativeText, 2200),
                        abbreviate(analysisText, 2200)
                ),
                500
        );

        if (response == null || response.isBlank()) {
            return null;
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(extractJsonObject(response));
            String subject = normalizeUploadSubject(root.path("subject").asText(), fallback.inferredSubject());
            String unit = cleanAnalysisLabel(root.path("unit").asText(), fallback.inferredUnit());
            String difficulty = normalizeUploadDifficulty(root.path("difficulty").asText(), fallback.documentDifficulty());
            String confidence = normalizeUploadConfidence(root.path("confidence").asText(), fallback.confidence());
            List<String> tags = readUploadAnalysisTags(root.path("tags"), fallback.recommendedTags());

            if (!isSubjectAlignedConcept(subject, unit) && !"컴퓨터공학".equals(subject)) {
                unit = inferUploadedDocumentUnit(subject, conceptScores);
            }
            if (tags.isEmpty()) {
                tags = buildUploadRecommendedTags(subject, unit, conceptScores);
            }

            return new DocumentUploadAnalysis(subject, unit, tags, difficulty, confidence);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalizeUploadSubject(String value, String fallback) {
        String subject = normalizeWhitespace(value);
        if (buildComputerScienceSubjectKeywords().containsKey(subject)) {
            return subject;
        }
        return buildComputerScienceSubjectKeywords().containsKey(fallback) ? fallback : "컴퓨터공학";
    }

    private String normalizeUploadDifficulty(String value, String fallback) {
        String difficulty = normalizeWhitespace(value);
        if (List.of("쉬움", "보통", "어려움").contains(difficulty)) {
            return difficulty;
        }
        return List.of("쉬움", "보통", "어려움").contains(fallback) ? fallback : "보통";
    }

    private String normalizeUploadConfidence(String value, String fallback) {
        String confidence = normalizeWhitespace(value);
        if (List.of("낮음", "보통", "높음").contains(confidence)) {
            return confidence;
        }
        return List.of("낮음", "보통", "높음").contains(fallback) ? fallback : "보통";
    }

    private List<String> readUploadAnalysisTags(com.fasterxml.jackson.databind.JsonNode tagsNode, List<String> fallback) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode tagNode : tagsNode) {
                String tag = cleanAnalysisLabel(tagNode.asText(), "");
                if (isValidAnalysisTag(tag)) {
                    tags.add(tag);
                }
            }
        }
        if (tags.isEmpty()) {
            fallback.stream()
                    .map(tag -> cleanAnalysisLabel(tag, ""))
                    .filter(this::isValidAnalysisTag)
                    .forEach(tags::add);
        }
        return tags.stream().limit(8).toList();
    }

    private List<DocumentChunk> selectAnalysisRepresentativeChunks(List<DocumentChunk> chunks, int limit) {
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> contentChunks = chunks.stream()
                .filter(this::isAnalysisContentChunk)
                .toList();
        List<DocumentChunk> source = contentChunks.isEmpty() ? chunks : contentChunks;
        int targetLimit = Math.max(1, Math.min(limit, source.size()));

        LinkedHashSet<DocumentChunk> selected = new LinkedHashSet<>();
        int total = source.size();
        int[][] windows = {
                {0, Math.max(1, total / 3)},
                {Math.max(0, total / 3), Math.max(1, (total * 2) / 3)},
                {Math.max(0, (total * 2) / 3), total}
        };

        for (int[] window : windows) {
            source.subList(window[0], Math.max(window[0], window[1])).stream()
                    .max(Comparator.comparingDouble(this::scoreAnalysisChunk))
                    .ifPresent(selected::add);
        }

        source.stream()
                .sorted(Comparator.comparingDouble(this::scoreAnalysisChunk).reversed())
                .forEach(chunk -> {
                    if (selected.size() < targetLimit) {
                        selected.add(chunk);
                    }
                });

        return selected.stream()
                .limit(targetLimit)
                .toList();
    }

    private boolean isAnalysisContentChunk(DocumentChunk chunk) {
        String text = normalizeWhitespace(chunk.getChunkText());
        if (text.length() < 90) {
            return false;
        }
        if (isStructuralAnalysisText(text)) {
            return false;
        }
        return scoreAnalysisChunk(chunk) >= 2.0;
    }

    private double scoreAnalysisChunk(DocumentChunk chunk) {
        String text = normalizeWhitespace(chunk.getChunkText());
        String lower = text.toLowerCase(Locale.ROOT);
        double score = 0.0;

        if (text.length() >= 180) score += 1.0;
        if (text.length() >= 360) score += 0.8;
        if (text.matches(".*(다\\.|니다\\.|이다\\.|한다\\.|된다\\.|있다\\.|없다\\.).*")) score += 1.2;
        if (text.matches(".*(정의|특징|구조|동작|과정|원리|비교|예를 들어|사용|관리|처리|설명).*")) score += 1.0;
        if (countComputerScienceKeywordHits(lower) >= 2) score += 1.2;
        if (countComputerScienceKeywordHits(lower) >= 5) score += 1.0;
        if (isStructuralAnalysisText(text)) score -= 3.0;
        if (lower.matches(".*\\b(section|chapter|contents|table of contents)\\b.*") || text.matches(".*(목차|차례|페이지).*")) score -= 1.5;

        return score;
    }

    private boolean isStructuralAnalysisText(String text) {
        String normalized = normalizeWhitespace(text);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.length() < 60) {
            return true;
        }
        if (lower.matches("^(section|chapter|contents|table of contents)\\b.*") || normalized.matches("^(목차|차례|강의목표|학습목표)\\b.*")) {
            return true;
        }
        int structuralHits = 0;
        for (String marker : List.of("목차", "차례", "section", "chapter", "page", "페이지", "그림", "표 ")) {
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
                structuralHits++;
            }
        }
        boolean hasSentence = normalized.matches(".*(다\\.|니다\\.|이다\\.|한다\\.|된다\\.|있다\\.|없다\\.).*");
        return structuralHits >= 2 && !hasSentence;
    }

    private String inferUploadedDocumentSubject(RagDocument document, String analysisText, Map<String, Integer> conceptScores) {
        String text = (document.getTitle() + " " + analysisText).toLowerCase(Locale.ROOT);
        Map<String, List<String>> subjectKeywords = buildComputerScienceSubjectKeywords();

        String bestSubject = "컴퓨터공학";
        int bestScore = 0;
        int secondScore = 0;
        for (Map.Entry<String, List<String>> entry : subjectKeywords.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                score += countKeywordHits(text, keyword.toLowerCase(Locale.ROOT));
            }
            score += subjectConceptBonus(entry.getKey(), conceptScores);
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestSubject = entry.getKey();
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        if (bestScore < 2) {
            return "컴퓨터공학";
        }
        if (bestScore == secondScore && bestScore < 5) {
            return "컴퓨터공학";
        }
        return bestSubject;
    }

    private int subjectConceptBonus(String subject, Map<String, Integer> conceptScores) {
        Map<String, List<String>> subjectConcepts = Map.of(
                "운영체제", List.of("프로세스 관리", "스레드", "CPU 스케줄링", "메모리 관리", "가상 메모리", "페이징"),
                "데이터베이스", List.of("SQL", "정규화", "트랜잭션", "조인", "기본키", "외래키"),
                "알고리즘", List.of("정렬 알고리즘", "그래프 탐색", "재귀 알고리즘", "시간 복잡도", "동적 계획법"),
                "인공지능", List.of("머신러닝", "신경망", "상태 공간 탐색", "지식 표현", "추론"),
                "네트워크", List.of("TCP/IP", "라우팅", "소켓 통신", "패킷", "HTTP"),
                "소프트웨어공학", List.of("요구사항 분석", "설계 패턴", "테스트", "UML", "애자일"),
                "컴퓨터구조", List.of("CPU", "캐시", "명령어", "파이프라인", "메모리 계층"),
                "프로그래밍", List.of("클래스", "객체", "상속", "함수", "예외 처리"),
                "데이터분석", List.of("Pandas", "DataFrame", "Series", "NumPy", "데이터 전처리", "데이터 시각화"),
                "RAG 시스템", List.of("RAG", "임베딩", "pgvector", "GPT Vision", "로컬 LLM", "벡터 검색")
        );
        return subjectConcepts.getOrDefault(subject, List.of()).stream()
                .mapToInt(concept -> conceptScores.getOrDefault(concept, 0))
                .sum();
    }

    private String inferUploadedDocumentUnit(String inferredSubject, Map<String, Integer> conceptScores) {
        return conceptScores.entrySet().stream()
                .filter(entry -> "컴퓨터공학".equals(inferredSubject) || isSubjectAlignedConcept(inferredSubject, entry.getKey()))
                .filter(entry -> isValidAnalysisTag(entry.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> fallbackUnitForSubject(inferredSubject, conceptScores));
    }

    private String fallbackUnitForSubject(String inferredSubject, Map<String, Integer> conceptScores) {
        return conceptScores.entrySet().stream()
                .filter(entry -> isValidAnalysisTag(entry.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> switch (inferredSubject) {
                    case "운영체제" -> "프로세스 관리";
                    case "데이터베이스" -> "데이터 모델";
                    case "알고리즘" -> "알고리즘 설계";
                    case "인공지능" -> "인공지능 개념";
                    case "네트워크" -> "네트워크 통신";
                    case "소프트웨어공학" -> "소프트웨어 개발";
                    case "컴퓨터구조" -> "컴퓨터 시스템 구조";
                    case "프로그래밍" -> "프로그래밍 기초";
                    case "데이터분석" -> "Pandas 데이터 처리";
                    case "RAG 시스템" -> "RAG 구성 요소";
                    default -> "핵심 개념";
                });
    }

    private List<String> buildUploadRecommendedTags(String inferredSubject, String inferredUnit, Map<String, Integer> conceptScores) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (isValidAnalysisTag(inferredUnit)) {
            tags.add(inferredUnit);
        }

        conceptScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .filter(entry -> "컴퓨터공학".equals(inferredSubject) || isSubjectAlignedConcept(inferredSubject, entry.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(this::isValidAnalysisTag)
                .forEach(tags::add);

        if (tags.size() < 3) {
            defaultTagsForSubject(inferredSubject).forEach(tags::add);
        }

        return tags.stream()
                .filter(this::isValidAnalysisTag)
                .distinct()
                .limit(8)
                .toList();
    }

    private boolean isSubjectAlignedConcept(String subject, String concept) {
        return switch (subject) {
            case "운영체제" -> List.of("프로세스", "스레드", "스케줄링", "메모리", "페이징", "페이지", "TLB", "교착상태", "파일 시스템").stream().anyMatch(concept::contains);
            case "데이터베이스" -> List.of("SQL", "정규화", "트랜잭션", "조인", "키", "관계", "테이블", "인덱스").stream().anyMatch(concept::contains);
            case "알고리즘" -> List.of("정렬", "탐색", "그래프", "재귀", "복잡도", "동적 계획", "해시", "트리").stream().anyMatch(concept::contains);
            case "인공지능" -> List.of("머신러닝", "딥러닝", "신경망", "탐색", "지식 표현", "추론", "분류", "회귀").stream().anyMatch(concept::contains);
            case "네트워크" -> List.of("TCP", "IP", "라우팅", "소켓", "패킷", "HTTP", "DNS", "프로토콜").stream().anyMatch(concept::contains);
            case "소프트웨어공학" -> List.of("요구사항", "설계", "테스트", "UML", "애자일", "품질", "유지보수").stream().anyMatch(concept::contains);
            case "컴퓨터구조" -> List.of("CPU", "캐시", "명령어", "파이프라인", "메모리 계층", "레지스터").stream().anyMatch(concept::contains);
            case "프로그래밍" -> List.of("클래스", "객체", "상속", "함수", "변수", "예외", "인터페이스").stream().anyMatch(concept::contains);
            case "데이터분석" -> List.of("Pandas", "DataFrame", "Series", "NumPy", "전처리", "시각화", "데이터 분석").stream().anyMatch(concept::contains);
            case "RAG 시스템" -> List.of("RAG", "임베딩", "pgvector", "GPT Vision", "로컬 LLM", "벡터 검색", "청크").stream().anyMatch(concept::contains);
            default -> true;
        };
    }

    private List<String> defaultTagsForSubject(String subject) {
        return switch (subject) {
            case "운영체제" -> List.of("프로세스 관리", "CPU 스케줄링", "메모리 관리", "가상 메모리", "파일 시스템");
            case "데이터베이스" -> List.of("SQL", "정규화", "트랜잭션", "조인", "기본키");
            case "알고리즘" -> List.of("정렬 알고리즘", "그래프 탐색", "재귀 알고리즘", "시간 복잡도", "동적 계획법");
            case "인공지능" -> List.of("머신러닝", "상태 공간 탐색", "지식 표현", "추론", "신경망");
            case "네트워크" -> List.of("TCP/IP", "라우팅", "소켓 통신", "패킷", "프로토콜");
            case "소프트웨어공학" -> List.of("요구사항 분석", "소프트웨어 설계", "테스트", "UML", "애자일");
            case "컴퓨터구조" -> List.of("CPU", "캐시", "명령어", "파이프라인", "메모리 계층");
            case "프로그래밍" -> List.of("클래스", "객체", "상속", "함수", "예외 처리");
            case "데이터분석" -> List.of("Pandas", "DataFrame", "Series", "NumPy", "데이터 전처리");
            case "RAG 시스템" -> List.of("RAG", "임베딩", "pgvector", "GPT Vision", "로컬 LLM");
            default -> List.of("컴퓨터공학", "핵심 개념", "시스템 구조");
        };
    }

    private String inferUploadDocumentDifficulty(List<DocumentChunk> chunks, String text, Map<String, Integer> conceptScores) {
        String normalized = normalizeWhitespace(text).toLowerCase(Locale.ROOT);
        int definitionSignals = countAny(normalized, List.of("정의", "개요", "기초", "소개", "basic", "introduction", "overview"));
        int mediumSignals = countAny(normalized, List.of("비교", "구조", "예제", "예시", "동작", "과정", "구현", "architecture", "example"));
        int hardSignals = countAny(normalized, List.of("수식", "증명", "복잡도", "최적화", "파이프라인", "정규형", "손실 함수", "gradient", "complexity", "optimization"));
        long richChunkCount = chunks.stream().filter(this::isAnalysisContentChunk).count();
        int conceptVariety = (int) conceptScores.values().stream().filter(score -> score > 0).count();

        int score = 0;
        score += Math.min(3, hardSignals);
        score += Math.min(2, conceptVariety / 5);
        if (richChunkCount >= 12) score += 1;
        if (mediumSignals >= 4) score += 1;
        if (definitionSignals >= 5 && hardSignals <= 1) score -= 1;

        if (score >= 6 && hardSignals >= 4 && richChunkCount >= 8) return "어려움";
        if (score >= 2) return "보통";
        return "쉬움";
    }

    private Map<String, Integer> scoreComputerScienceConcepts(String text) {
        String lower = normalizeWhitespace(text).toLowerCase(Locale.ROOT);
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : buildComputerScienceConceptKeywords().entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                score += countKeywordHits(lower, keyword.toLowerCase(Locale.ROOT));
            }
            if (score > 0) {
                scores.put(entry.getKey(), score);
            }
        }
        return scores;
    }

    private Map<String, List<String>> buildComputerScienceSubjectKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("운영체제", List.of("operating system", "os", "process", "thread", "memory management", "virtual memory", "paging", "scheduling", "deadlock", "프로세스", "스레드", "메모리", "페이징", "스케줄링", "교착상태", "운영체제"));
        keywords.put("데이터베이스", List.of("database", "sql", "normalization", "transaction", "join", "table", "primary key", "foreign key", "데이터베이스", "정규화", "트랜잭션", "테이블", "기본키", "외래키", "조인"));
        keywords.put("알고리즘", List.of("algorithm", "sorting", "search", "graph", "recursion", "time complexity", "dynamic programming", "알고리즘", "정렬", "탐색", "그래프", "재귀", "시간 복잡도", "동적 계획법"));
        keywords.put("인공지능", List.of("artificial intelligence", "ai", "machine learning", "deep learning", "neural network", "knowledge representation", "inference", "state space", "인공지능", "머신러닝", "딥러닝", "신경망", "지식 표현", "추론", "상태 공간"));
        keywords.put("네트워크", List.of("network", "tcp", "ip", "routing", "socket", "packet", "http", "dns", "네트워크", "라우팅", "소켓", "패킷", "프로토콜"));
        keywords.put("소프트웨어공학", List.of("software engineering", "requirements", "uml", "design pattern", "testing", "agile", "maintenance", "소프트웨어공학", "요구사항", "설계 패턴", "테스트", "애자일", "유지보수"));
        keywords.put("컴퓨터구조", List.of("computer architecture", "cpu", "cache", "instruction", "pipeline", "register", "memory hierarchy", "컴퓨터구조", "캐시", "명령어", "파이프라인", "레지스터", "메모리 계층"));
        keywords.put("프로그래밍", List.of("programming", "class", "object", "inheritance", "function", "variable", "exception", "프로그래밍", "클래스", "객체", "상속", "함수", "변수", "예외"));
        keywords.put("데이터분석", List.of("data analysis", "data science", "pandas", "numpy", "dataframe", "series", "matplotlib", "seaborn", "데이터 분석", "데이터분석", "판다스", "넘파이", "데이터프레임", "시리즈", "전처리", "시각화"));
        keywords.put("RAG 시스템", List.of("rag", "retrieval augmented generation", "embedding", "vector database", "pgvector", "gpt vision", "local llm", "ollama", "chunk", "벡터 db", "벡터DB", "임베딩", "벡터 검색", "청크", "로컬 llm", "표/이미지", "시각 자료"));
        return keywords;
    }

    private Map<String, List<String>> buildComputerScienceConceptKeywords() {
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("프로세스 관리", List.of("process management", "process", "프로세스 관리", "프로세스"));
        keywords.put("스레드", List.of("thread", "스레드"));
        keywords.put("CPU 스케줄링", List.of("cpu scheduling", "scheduling", "scheduler", "cpu 스케줄링", "스케줄링"));
        keywords.put("메모리 관리", List.of("memory management", "메모리 관리", "주기억장치", "메모리 할당"));
        keywords.put("가상 메모리", List.of("virtual memory", "가상 메모리"));
        keywords.put("페이징", List.of("paging", "page table", "page fault", "tlb", "페이징", "페이지 테이블", "페이지 부재"));
        keywords.put("교착상태", List.of("deadlock", "교착상태"));
        keywords.put("파일 시스템", List.of("file system", "파일 시스템"));
        keywords.put("SQL", List.of("sql", "select", "insert", "update", "delete", "SQL"));
        keywords.put("정규화", List.of("normalization", "normal form", "정규화", "정규형", "함수 종속"));
        keywords.put("트랜잭션", List.of("transaction", "acid", "commit", "rollback", "트랜잭션"));
        keywords.put("조인", List.of("join", "inner join", "outer join", "조인"));
        keywords.put("기본키", List.of("primary key", "기본키", "primary"));
        keywords.put("외래키", List.of("foreign key", "외래키"));
        keywords.put("인덱스", List.of("index", "b-tree", "인덱스"));
        keywords.put("정렬 알고리즘", List.of("sorting", "sort", "bubble sort", "quick sort", "merge sort", "정렬", "버블 정렬", "퀵 정렬", "병합 정렬", "삽입 정렬"));
        keywords.put("그래프 탐색", List.of("graph search", "bfs", "dfs", "그래프 탐색", "너비 우선", "깊이 우선"));
        keywords.put("재귀 알고리즘", List.of("recursion", "recursive", "재귀"));
        keywords.put("시간 복잡도", List.of("time complexity", "big-o", "시간 복잡도", "빅오"));
        keywords.put("동적 계획법", List.of("dynamic programming", "dp", "동적 계획법"));
        keywords.put("머신러닝", List.of("machine learning", "머신러닝", "기계학습"));
        keywords.put("신경망", List.of("neural network", "deep learning", "신경망", "딥러닝"));
        keywords.put("상태 공간 탐색", List.of("state space", "search space", "상태 공간", "탐색 공간"));
        keywords.put("지식 표현", List.of("knowledge representation", "지식 표현"));
        keywords.put("추론", List.of("inference", "reasoning", "추론"));
        keywords.put("분류", List.of("classification", "classifier", "분류"));
        keywords.put("회귀", List.of("regression", "회귀"));
        keywords.put("TCP/IP", List.of("tcp/ip", "tcp", "ip", "TCP", "IP"));
        keywords.put("라우팅", List.of("routing", "router", "라우팅", "라우터"));
        keywords.put("소켓 통신", List.of("socket", "소켓"));
        keywords.put("패킷", List.of("packet", "패킷"));
        keywords.put("프로토콜", List.of("protocol", "프로토콜"));
        keywords.put("요구사항 분석", List.of("requirements analysis", "requirement", "요구사항", "요구 분석"));
        keywords.put("소프트웨어 설계", List.of("software design", "architecture", "소프트웨어 설계", "아키텍처"));
        keywords.put("설계 패턴", List.of("design pattern", "설계 패턴"));
        keywords.put("테스트", List.of("testing", "test case", "테스트"));
        keywords.put("UML", List.of("uml", "UML"));
        keywords.put("애자일", List.of("agile", "scrum", "애자일", "스크럼"));
        keywords.put("CPU", List.of("cpu", "processor", "CPU", "프로세서"));
        keywords.put("캐시", List.of("cache", "캐시"));
        keywords.put("명령어", List.of("instruction", "isa", "명령어"));
        keywords.put("파이프라인", List.of("pipeline", "pipelining", "파이프라인"));
        keywords.put("메모리 계층", List.of("memory hierarchy", "메모리 계층"));
        keywords.put("클래스", List.of("class", "클래스"));
        keywords.put("객체", List.of("object", "객체"));
        keywords.put("상속", List.of("inheritance", "상속"));
        keywords.put("함수", List.of("function", "method", "함수", "메서드"));
        keywords.put("변수", List.of("variable", "변수"));
        keywords.put("예외 처리", List.of("exception", "예외", "예외 처리"));
        keywords.put("Pandas", List.of("pandas", "판다스"));
        keywords.put("DataFrame", List.of("dataframe", "data frame", "데이터프레임"));
        keywords.put("Series", List.of("series", "시리즈"));
        keywords.put("NumPy", List.of("numpy", "넘파이"));
        keywords.put("데이터 전처리", List.of("preprocessing", "cleaning", "결측치", "전처리", "정제"));
        keywords.put("데이터 시각화", List.of("visualization", "matplotlib", "seaborn", "plot", "시각화", "그래프"));
        keywords.put("RAG", List.of("rag", "retrieval augmented generation", "검색 증강 생성"));
        keywords.put("임베딩", List.of("embedding", "임베딩", "벡터화"));
        keywords.put("pgvector", List.of("pgvector", "vector database", "벡터 db", "벡터DB", "벡터 데이터베이스"));
        keywords.put("GPT Vision", List.of("gpt vision", "vision", "시각 자료", "이미지 분석", "표/이미지"));
        keywords.put("로컬 LLM", List.of("local llm", "ollama", "로컬 llm", "로컬 LLM"));
        return keywords;
    }

    private int countKeywordHits(String lowerText, String lowerKeyword) {
        if (lowerText == null || lowerKeyword == null || lowerKeyword.isBlank()) {
            return 0;
        }
        if (lowerKeyword.matches("[a-z0-9+#./-]{1,3}")) {
            Matcher matcher = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(lowerKeyword) + "(?![a-z0-9])")
                    .matcher(lowerText);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
        return countOccurrences(lowerText, lowerKeyword);
    }

    private int countComputerScienceKeywordHits(String lowerText) {
        return buildComputerScienceConceptKeywords().values().stream()
                .flatMap(List::stream)
                .mapToInt(keyword -> lowerText.contains(keyword.toLowerCase(Locale.ROOT)) ? 1 : 0)
                .sum();
    }

    private int countAny(String lowerText, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            count += countOccurrences(lowerText, keyword.toLowerCase(Locale.ROOT));
        }
        return count;
    }

    private int countOccurrences(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(keyword, index)) >= 0) {
            count++;
            index += keyword.length();
        }
        return count;
    }

    private boolean isValidAnalysisTag(String value) {
        String tag = normalizeWhitespace(value);
        if (tag.length() < 2 || tag.length() > 30) {
            return false;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        Set<String> blocked = Set.of(
                "목차", "차례", "section", "chapter", "있는", "없는", "그리고", "또는", "본문", "페이지", "그림", "표", "예제", "문제", "분류 필요",
                "contents", "table of contents", "page", "figure", "example", "exercise", "핵심 개념"
        );
        if (blocked.contains(lower) || blocked.contains(tag)) {
            return false;
        }
        return !tag.matches("^[0-9.() -]+$")
                && !tag.matches("^(은|는|이|가|을|를|의|와|과|도|로|으로)$")
                && !tag.matches("(?i)^section\\s*\\d+$")
                && !tag.matches("(?i)^chapter\\s*\\d+$");
    }

    private String cleanAnalysisLabel(String value, String fallback) {
        String cleaned = normalizeWhitespace(value)
                .replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)^section\\s*\\d+\\s*", "")
                .replaceAll("(?i)^chapter\\s*\\d+\\s*", "")
                .trim();
        if (!isValidAnalysisTag(cleaned)) {
            return fallback;
        }
        if (cleaned.length() > 28) {
            return cleaned.substring(0, 28).trim();
        }
        return cleaned;
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
        if (text == null) {
            return "";
        }

        return text
                .replace('\u00A0', ' ')
                .replace("▮", " ")
                .replace("■", " ")
                .replace("□", " ")
                .replace("●", " ")
                .replace("○", " ")
                .replace("▶", " ")
                .replace("◆", " ")
                .replace("•", " ")
                .replaceAll("[\\u0000-\\u001F]", " ")
                .replaceAll("([가-힣])([A-Za-z])", "$1 $2")
                .replaceAll("([A-Za-z])([가-힣])", "$1 $2")
                .replaceAll("([가-힣])([0-9])", "$1 $2")
                .replaceAll("([0-9])([가-힣])", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
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
            int count,
            Set<String> excludedQuestionFingerprints
    ) {
        List<RagGeneratedQuestionResponse> llmQuestions = buildAdaptiveQuestionSetWithOllama(
                rawText,
                conceptEvidence,
                evidenceSentences,
                type,
                count,
                excludedQuestionFingerprints
        );
        if (!llmQuestions.isEmpty()) {
            return llmQuestions;
        }

        List<RagGeneratedQuestionResponse> questions = new ArrayList<>();
        List<ConceptEvidence> pool = conceptEvidence.isEmpty()
                ? evidenceSentences.stream()
                .filter(sentence -> !isBrokenGeneratedText(sentence))
                .map(sentence -> new ConceptEvidence(extractLeadingConcept(sentence), sentence))
                .filter(evidence -> isUsableConcept(evidence.concept()))
                .toList()
                : conceptEvidence.stream()
                .map(evidence -> new ConceptEvidence(normalizeConcept(evidence.concept()), evidence.explanation()))
                .filter(evidence -> isUsableConcept(evidence.concept()))
                .filter(evidence -> !isBrokenGeneratedText(evidence.explanation()))
                .distinct()
                .toList();
        if (pool.isEmpty()) {
            pool = List.of(new ConceptEvidence("핵심 개념", "문서의 핵심 개념을 이해하고 구분한 뒤 상황에 적용할 수 있어야 합니다."));
        }

        for (int i = 0; i < pool.size() && questions.size() < count; i++) {
            ConceptEvidence evidence = pool.get(i);
            String selectedType = selectQuestionType(type, questions.size());
            RagGeneratedQuestionResponse candidate;
            if ("multiple_choice".equals(selectedType)) {
                candidate = buildMultipleChoiceQuestion(questions.size() + 1, evidence, pool);
            } else if ("ox".equals(selectedType)) {
                candidate = buildOxQuestion(questions.size() + 1, evidence, pool);
            } else {
                candidate = buildShortAnswerQuestion(questions.size() + 1, evidence);
            }
            if (!isDuplicateQuestion(candidate, questions, excludedQuestionFingerprints)) {
                questions.add(candidate);
            }
        }

        while (questions.size() < count) {
            String fallback = evidenceSentences.isEmpty() ? "문서 핵심 개념을 설명하세요." : evidenceSentences.get(Math.min(questions.size(), evidenceSentences.size() - 1));
            String selectedType = selectQuestionType(type, questions.size());
            RagGeneratedQuestionResponse candidate = buildFallbackQuestion(questions.size() + 1, selectedType, fallback);
            if (isDuplicateQuestion(candidate, questions, excludedQuestionFingerprints)) {
                candidate = buildFallbackQuestion(questions.size() + 1, selectedType, fallback + " 추가 관점");
            }
            questions.add(candidate);
        }

        ensureUnderstandingLevelCoverage(questions, type, count, pool);
        makeQuestionsUnique(questions, type, pool, excludedQuestionFingerprints);
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
            int count,
            Set<String> excludedQuestionFingerprints
    ) {
        if (!ollamaService.isEnabled()) {
            return List.of();
        }

        String evidenceBlock = conceptEvidence.stream()
                .limit(10)
                .map(item -> "- " + normalizeWhitespace(item.concept()) + ": " + normalizeWhitespace(item.explanation()))
                .filter(line -> !isBrokenGeneratedText(line))
                .collect(Collectors.joining("\n"));
        String sentenceBlock = evidenceSentences.stream()
                .map(this::normalizeWhitespace)
                .filter(sentence -> !isBrokenGeneratedText(sentence))
                .limit(10)
                .map(sentence -> "- " + sentence)
                .collect(Collectors.joining("\n"));
        String excludedQuestionBlock = excludedQuestionFingerprints == null || excludedQuestionFingerprints.isEmpty()
                ? "- none"
                : excludedQuestionFingerprints.stream()
                .limit(20)
                .map(question -> "- " + question)
                .collect(Collectors.joining("\n"));
        String rawExcerpt = abbreviate(rawText, 2200);
        String studyNote = buildStudyNoteForQuiz(evidenceBlock, sentenceBlock, rawExcerpt);

        String systemPrompt = """
                You are a Korean computer-science exam question writer.
                Return strict JSON only. Do not use markdown. Do not add any text outside JSON.

                Create high-quality Korean quiz questions from the clean study note first.
                Use the PDF evidence only as supporting ground truth.
                Do not copy broken PDF fragments. Rewrite the evidence into natural Korean.

                Global quality rules:
                - Remove or ignore broken symbols such as ▮, ■, □, ●, ○, bullets, page headers, footers, and layout artifacts.
                - Do not use garbled text such as "Feature Data W", unfinished headings, page numbers, or copied fragments.
                - Do not create a question if the source sentence is too broken; use another evidence item instead.
                - Every question must test one clear computer-science concept.
                - Use natural Korean spacing and grammar.
                - Do not invent facts outside the evidence.
                - Do not repeat or paraphrase the previously generated questions listed in the user message.
                - Prefer facts and concepts from [Clean Study Note]. Do not turn learning objectives, table-of-contents items, or chapter goals into answers.

                Type rules:
                - Each item type must be one of multiple_choice, short_answer, or ox.
                - Preferred mode: %s.
                - Make exactly %d items.
                - If the requested count is 3 or more, include CONCEPT_UNDERSTANDING, CONCEPT_DISTINCTION, and CONCEPT_APPLICATION at least once.

                Multiple choice rules:
                - The question must ask one concept, role, difference, or application.
                - Include exactly 4 choices.
                - Each choice must be a short, complete Korean phrase or sentence.
                - Choices should be similar length, plausible, and not duplicated.
                - correctAnswer must exactly match one of the choices.
                - Do not paste long evidence sentences into choices.

                O/X rules:
                - The question field must be a complete declarative statement, not a question.
                - choices must be exactly ["O", "X"].
                - correctAnswer must be either "O" or "X".
                - The statement must be natural Korean and must not contain broken symbols or copied fragments.

                Short answer rules:
                - choices must be an empty array.
                - The question must require explanation, comparison, reason, relationship, or example-based application.
                - The question, correctAnswer, modelAnswer, explanation, sourceEvidence, and conceptTag must focus on the same exact concept.
                - conceptTag must be a natural Korean learning concept label, not copied PDF text.
                  Bad: "vi 명령어 입력모드전환". Good: "vi 입력 모드 전환 명령어".
                  Bad: "데이터베이스정규화제2정규형". Good: "제2정규형과 부분 함수 종속".
                - The question must be a natural Korean sentence. Do not paste conceptTag awkwardly at the front.
                - If the answer explains subcommands, subfunctions, options, or listed items, the question must explicitly ask about those items, not only the broader parent topic.
                - Do not ask "X가 무엇인지 정의와 핵심 특징" unless the answer actually defines X itself.
                - modelAnswer and correctAnswer must be complete Korean explanatory sentences.
                - Do not create O/X or yes/no questions as short_answer.

                JSON shape:
                {"questions":[{"type":"multiple_choice","question":"...","choices":["..."],"correctAnswer":"...","modelAnswer":"...","explanation":"...","sourceEvidence":"...","difficulty":"easy","conceptTag":"...","understandingLevel":"CONCEPT_DISTINCTION"}]}
                """.formatted(type, count);

        String userPrompt = """
                [Clean Study Note]
                %s

                [Concept Evidence]
                %s

                [Previously Generated Questions To Avoid]
                %s

                [Supporting Sentences]
                %s

                [Raw Excerpt]
                %s
                """.formatted(studyNote, evidenceBlock, excludedQuestionBlock, sentenceBlock, rawExcerpt);

        String response = ollamaService.generateJson(
                systemPrompt,
                userPrompt,
                Math.max(1200, count * 380)
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
                question = sanitizeGeneratedQuestion(question, type, conceptEvidence, evidenceSentences);

                if (question != null
                        && isValidQuestionType(question.getType(), type)
                        && isAcceptableQuizQuestion(question)
                        && !isDuplicateQuestion(question, questions, excludedQuestionFingerprints)) {
                    questions.add(question);
                }

                if (questions.size() == count) {
                    break;
                }
            }
            fillMissingLlmQuestions(questions, type, count, conceptEvidence, evidenceSentences, excludedQuestionFingerprints);
            ensureUnderstandingLevelCoverage(questions, type, count, buildConceptPool(conceptEvidence, evidenceSentences, questions));
            makeQuestionsUnique(questions, type, buildConceptPool(conceptEvidence, evidenceSentences, questions), excludedQuestionFingerprints);
            return questions.isEmpty() ? List.of() : questions;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildStudyNoteForQuiz(String evidenceBlock, String sentenceBlock, String rawExcerpt) {
        String fallback = buildFallbackStudyNote(evidenceBlock, sentenceBlock, rawExcerpt);
        if (!ollamaService.isEnabled()) {
            return fallback;
        }

        String systemPrompt = """
                You are a Korean computer-science teaching assistant.
                Convert noisy PDF extraction text into a clean study note for quiz generation.
                Return plain Korean study notes only. Do not return JSON. Do not use markdown tables.

                Rules:
                - Do not copy broken PDF text verbatim.
                - Fix spacing and grammar naturally.
                - Remove page numbers, table-of-contents items, learning objectives, chapter goals, headers, footers, and layout artifacts.
                - Ignore sentences ending with goals such as "알아본다", "학습한다", "이해한다", "살펴본다" unless they contain an actual definition.
                - Keep only definitions, roles, differences, principles, commands, functions, examples, and cause-effect relationships.
                - Do not invent facts outside the evidence.
                - Write 8 to 12 concise bullet lines.
                - Each bullet should contain one quiz-worthy concept.
                """;

        String userPrompt = """
                [Concept Evidence]
                %s

                [Supporting Sentences]
                %s

                [Raw PDF Excerpt]
                %s
                """.formatted(evidenceBlock, sentenceBlock, rawExcerpt);

        String response = ollamaService.generateJson(systemPrompt, userPrompt, 1400);

        String sanitized = sanitizeStudyNoteForQuiz(response);
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private String buildFallbackStudyNote(String evidenceBlock, String sentenceBlock, String rawExcerpt) {
        String combined = String.join("\n", evidenceBlock, sentenceBlock, rawExcerpt);
        List<String> notes = splitLines(combined).stream()
                .map(this::normalizeWhitespace)
                .filter(line -> !isBadStudyNoteLine(line))
                .distinct()
                .limit(12)
                .toList();
        if (notes.isEmpty()) {
            return normalizeWhitespace(abbreviate(rawExcerpt, 1000));
        }
        return String.join("\n", notes);
    }

    private String sanitizeStudyNoteForQuiz(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String cleaned = response
                .replaceAll("(?s)^```(?:text|markdown)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        List<String> lines = splitLines(cleaned).stream()
                .map(line -> normalizeWhitespace(line).replaceAll("^[-*•]+\\s*", ""))
                .filter(line -> !isBadStudyNoteLine(line))
                .distinct()
                .limit(14)
                .toList();
        return String.join("\n", lines);
    }

    private boolean isBadStudyNoteLine(String value) {
        String normalized = normalizeWhitespace(value);
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() < 12) {
            return true;
        }
        if (containsPdfNoise(normalized)) {
            return true;
        }
        if (lower.startsWith("section") || lower.startsWith("chapter") || lower.startsWith("page")) {
            return true;
        }
        if (normalized.startsWith("목차") || normalized.startsWith("차례") || normalized.startsWith("학습 목표") || normalized.startsWith("강의 목표")) {
            return true;
        }
        return normalized.endsWith("알아본다")
                || normalized.endsWith("알아본다.")
                || normalized.endsWith("학습한다")
                || normalized.endsWith("학습한다.")
                || normalized.endsWith("이해한다")
                || normalized.endsWith("이해한다.")
                || normalized.endsWith("살펴본다")
                || normalized.endsWith("살펴본다.")
                || normalized.contains("에 대해 알아")
                || normalized.contains("에 대해 학습")
                || normalized.contains("에 대해 이해");
    }

    private boolean isAcceptableQuizQuestion(RagGeneratedQuestionResponse question) {
        if (question == null) {
            return false;
        }
        if (containsPdfNoise(question.getQuestion())
                || containsPdfNoise(question.getCorrectAnswer())
                || containsPdfNoise(question.getModelAnswer())
                || containsPdfNoise(question.getExplanation())) {
            return false;
        }
        if ("multiple_choice".equals(question.getType()) && question.getChoices() != null) {
            return question.getChoices().stream().noneMatch(this::containsPdfNoise);
        }
        return true;
    }

    private boolean containsPdfNoise(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.isBlank()) {
            return true;
        }
        return normalized.contains("▮")
                || normalized.contains("■")
                || normalized.contains("□")
                || normalized.contains("Feature Data W")
                || normalized.matches(".*[A-Za-z]{2,}\s+[A-Za-z]{1,}\s+[가-힣]{1,}.*")
                || normalized.length() > 260;
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
            List<String> evidenceSentences,
            Set<String> excludedQuestionFingerprints
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
            RagGeneratedQuestionResponse candidate;
            if ("multiple_choice".equals(selectedType)) {
                candidate = buildMultipleChoiceQuestion(index + 1, evidence, pool);
            } else if ("ox".equals(selectedType)) {
                candidate = buildOxQuestion(index + 1, evidence, pool);
            } else {
                candidate = buildShortAnswerQuestion(index + 1, evidence);
            }
            if (isDuplicateQuestion(candidate, questions, excludedQuestionFingerprints)) {
                candidate = buildFallbackQuestion(index + 1, selectedType, evidence.explanation() + " 다른 관점");
            }
            questions.add(candidate);
        }
    }

    private Set<String> buildExcludedQuestionFingerprints(Long sessionId, List<Long> documentIds) {
        if (sessionId == null || documentIds == null || documentIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> selectedDocumentIds = documentIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (selectedDocumentIds.isEmpty()) {
            return Set.of();
        }

        return sessionQuizRepository.findBySessionIdOrderByCreatedAtAscQuestionOrderAsc(sessionId).stream()
                .filter(quiz -> overlapsSourceDocuments(quiz, selectedDocumentIds))
                .map(SessionQuiz::getQuestion)
                .map(this::questionFingerprint)
                .filter(fingerprint -> !fingerprint.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean overlapsSourceDocuments(SessionQuiz quiz, Set<Long> selectedDocumentIds) {
        if (quiz.getDocumentId() != null && selectedDocumentIds.contains(quiz.getDocumentId())) {
            return true;
        }
        String sourceDocumentIdsJson = normalizeWhitespace(quiz.getSourceDocumentIdsJson());
        if (sourceDocumentIdsJson.isBlank()) {
            return false;
        }
        Matcher matcher = Pattern.compile("\\d+").matcher(sourceDocumentIdsJson);
        while (matcher.find()) {
            try {
                if (selectedDocumentIds.contains(Long.parseLong(matcher.group()))) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy ids.
            }
        }
        return false;
    }

    private boolean isDuplicateQuestion(
            RagGeneratedQuestionResponse candidate,
            List<RagGeneratedQuestionResponse> existingQuestions,
            Set<String> excludedQuestionFingerprints
    ) {
        String fingerprint = questionFingerprint(candidate == null ? "" : candidate.getQuestion());
        if (fingerprint.isBlank()) {
            return false;
        }
        if (excludedQuestionFingerprints != null && excludedQuestionFingerprints.contains(fingerprint)) {
            return true;
        }
        return existingQuestions.stream()
                .map(RagGeneratedQuestionResponse::getQuestion)
                .map(this::questionFingerprint)
                .anyMatch(fingerprint::equals);
    }

    private void makeQuestionsUnique(
            List<RagGeneratedQuestionResponse> questions,
            String requestedType,
            List<ConceptEvidence> pool,
            Set<String> excludedQuestionFingerprints
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            RagGeneratedQuestionResponse question = questions.get(i);
            String fingerprint = questionFingerprint(question.getQuestion());
            boolean duplicate = (excludedQuestionFingerprints != null && excludedQuestionFingerprints.contains(fingerprint))
                    || !seen.add(fingerprint);
            if (!duplicate) {
                continue;
            }

            RagGeneratedQuestionResponse replacement = withQuestionStem(
                    question,
                    buildAlternativeQuestionStem(question, i, requestedType)
            );
            String replacementFingerprint = questionFingerprint(replacement.getQuestion());
            if ((excludedQuestionFingerprints != null && excludedQuestionFingerprints.contains(replacementFingerprint))
                    || seen.contains(replacementFingerprint)) {
                ConceptEvidence evidence = pool.isEmpty()
                        ? new ConceptEvidence(defaultValue(question.getConceptTag(), "핵심 개념"), defaultValue(question.getModelAnswer(), question.getCorrectAnswer()))
                        : pool.get(i % pool.size());
                String selectedType = "mixed".equals(requestedType) ? question.getType() : requestedType;
                replacement = switch (selectedType) {
                    case "multiple_choice" -> buildMultipleChoiceQuestion(question.getOrder(), evidence, pool);
                    case "ox" -> buildOxQuestion(question.getOrder(), evidence, pool);
                    default -> buildShortAnswerQuestion(question.getOrder(), evidence);
                };
                replacement = withQuestionStem(replacement, buildAlternativeQuestionStem(replacement, i + 3, requestedType));
                replacementFingerprint = questionFingerprint(replacement.getQuestion());
            }
            seen.add(replacementFingerprint);
            questions.set(i, replacement);
        }
    }

    private String buildAlternativeQuestionStem(RagGeneratedQuestionResponse question, int index, String requestedType) {
        String concept = cleanShortAnswerConcept(defaultValue(question.getConceptTag(), "핵심 개념"));
        String type = normalizeQuestionType(defaultValue(question.getType(), requestedType));
        if ("multiple_choice".equals(type)) {
            return switch (index % 4) {
                case 0 -> "다음 중 " + concept + " 개념의 핵심 역할을 가장 정확히 설명한 것은 무엇인가요?";
                case 1 -> concept + "에 대해 문서 내용과 가장 일치하는 설명을 고르세요.";
                case 2 -> concept + " 개념을 다른 설명과 구분할 때 가장 알맞은 보기는 무엇인가요?";
                default -> "문서에서 설명한 " + concept + "의 특징으로 가장 적절한 것은 무엇인가요?";
            };
        }
        if ("ox".equals(type)) {
            String statement = selectOxStatementSource(question);
            return "다음 문장이 문서 내용과 일치하면 O, 일치하지 않으면 X를 고르세요.\n\"" + stripOuterSentence(statement) + "\"";
        }
        return switch (index % 3) {
            case 0 -> concept + "의 핵심 의미를 문서 근거와 연결해 설명하세요.";
            case 1 -> concept + "가 중요한 이유를 문서 내용에 근거해 설명하세요.";
            default -> concept + "의 역할과 특징을 함께 설명하세요.";
        };
    }

    private RagGeneratedQuestionResponse withQuestionStem(RagGeneratedQuestionResponse question, String stem) {
        return new RagGeneratedQuestionResponse(
                question.getOrder(),
                question.getType(),
                stem,
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

    private String questionFingerprint(String question) {
        return normalizeWhitespace(question)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]+", "")
                .trim();
    }

    private String stripOuterSentence(String value) {
        String statement = stripLeadingOxMarker(normalizeWhitespace(value))
                .replaceAll("^다음 설명이 맞으면 O, 틀리면 X를 고르세요\\.?", "")
                .replaceAll("^다음 문장이 문서 내용과 일치하면 O, 일치하지 않으면 X를 고르세요\\.?", "")
                .replaceAll("^\"|\"$", "")
                .trim();
        if (!statement.endsWith(".")) {
            statement = statement + ".";
        }
        return statement;
    }

    private RagGeneratedQuestionResponse sanitizeGeneratedQuestion(
            RagGeneratedQuestionResponse question,
            String requestedType,
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences
    ) {
        if (question == null) {
            return null;
        }

        List<ConceptEvidence> pool = buildConceptPool(conceptEvidence, evidenceSentences, List.of(question));
        ConceptEvidence evidence = selectRepairEvidence(question, pool);

        if ("short_answer".equals(question.getType())) {
            return buildSafeShortAnswerQuestion(
                    question.getOrder(),
                    question.getQuestion(),
                    question.getModelAnswer(),
                    question.getExplanation(),
                    question.getSourceEvidence(),
                    question.getDifficulty(),
                    question.getConceptTag(),
                    question.getUnderstandingLevel()
            );
        }

        if ("ox".equals(question.getType())) {
            if (!isHighQualityOxQuestion(question)) {
                return withOrder(buildOxQuestion(question.getOrder(), evidence, pool), question.getOrder());
            }
            return normalizeOxGeneratedQuestion(question);
        }

        if ("multiple_choice".equals(question.getType())) {
            if (!isHighQualityMultipleChoiceQuestion(question)) {
                return withOrder(buildMultipleChoiceQuestion(question.getOrder(), evidence, pool), question.getOrder());
            }
            return normalizeMultipleChoiceGeneratedQuestion(question);
        }

        return isValidQuestionType(question.getType(), requestedType) ? question : null;
    }

    private ConceptEvidence selectRepairEvidence(RagGeneratedQuestionResponse question, List<ConceptEvidence> pool) {
        if (pool.isEmpty()) {
            String fallback = normalizeWhitespace(question.getSourceEvidence());
            if (fallback.isBlank()) {
                fallback = normalizeWhitespace(question.getModelAnswer());
            }
            if (fallback.isBlank()) {
                fallback = "문서의 핵심 개념을 설명합니다.";
            }
            return new ConceptEvidence(inferConceptTag(fallback, fallback, fallback), fallback);
        }

        String compactTag = normalizeSearchToken(question.getConceptTag()).toLowerCase(Locale.ROOT);
        return pool.stream()
                .filter(evidence -> normalizeSearchToken(evidence.concept()).toLowerCase(Locale.ROOT).equals(compactTag))
                .findFirst()
                .orElse(pool.get(0));
    }

    private boolean isBrokenGeneratedText(String value) {
        String normalized = normalizeWhitespace(value);
        if (normalized.isBlank()) {
            return true;
        }
        String stripped = stripLeadingOxMarker(normalized)
                .replaceAll("^\"|\"$", "")
                .trim();
        return stripped.length() < 4
                || stripped.contains("▮")
                || stripped.contains("■")
                || stripped.contains("□")
                || stripped.contains("Feature Data W")
                || stripped.matches("^[,.;:，、].*")
                || startsWithBrokenKoreanFragment(stripped)
                || endsWithBrokenKoreanFragment(stripped)
                || hasDanglingCommaFragment(stripped)
                || stripped.matches(".*(다음과 같음|아래와 같음|주요 역할은|역할은|특징은|종류는)\\s*[.。\"']?$")
                || stripped.contains("로의 주요 역할");
    }

    private boolean isInvalidMultipleChoiceOption(String value) {
        String normalized = normalizeWhitespace(value);
        if (isBrokenGeneratedText(normalized)) {
            return true;
        }
        String stripped = normalized.replaceAll("^[A-Da-d]\\s*[.)．:]\\s*", "").trim();
        return stripped.length() < 12
                || stripped.matches("^[,.;:，、].*")
                || stripped.matches(".*(근거 없이|직접 일치하지|중요하지 않|일반화한 설명|문서 내용과 가장 일치|문서에서 중요하지).*")
                || hasDanglingCommaFragment(stripped)
                || stripped.matches(".*(프로그|시스|네트워|메모|인터페이|하드웨|소프트웨)\\s*$");
    }

    private boolean isHighQualityMultipleChoiceQuestion(RagGeneratedQuestionResponse question) {
        String stem = normalizeWhitespace(question.getQuestion());
        if (stem.length() < 12 || isBrokenGeneratedText(stem) || !looksLikeQuestionStem(stem)) {
            return false;
        }
        String conceptTag = cleanShortAnswerConcept(question.getConceptTag());
        String compactStem = stem.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String compactConcept = conceptTag.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (isUsableShortAnswerConcept(conceptTag)
                && !compactConcept.isBlank()
                && !compactStem.contains(compactConcept)) {
            return false;
        }
        if (question.getChoices() == null || question.getChoices().size() != 4) {
            return false;
        }

        List<String> cleanedChoices = question.getChoices().stream()
                .map(this::cleanMultipleChoiceOption)
                .filter(choice -> !choice.isBlank())
                .toList();
        if (cleanedChoices.size() != 4 || cleanedChoices.stream().anyMatch(this::isInvalidMultipleChoiceOption)) {
            return false;
        }

        Set<String> uniqueChoices = cleanedChoices.stream()
                .map(this::choiceFingerprint)
                .collect(Collectors.toSet());
        if (uniqueChoices.size() != 4) {
            return false;
        }

        String correct = cleanMultipleChoiceOption(question.getCorrectAnswer());
        if (isInvalidMultipleChoiceOption(correct)) {
            return false;
        }
        return cleanedChoices.stream().anyMatch(choice -> choice.equals(correct));
    }

    private RagGeneratedQuestionResponse normalizeMultipleChoiceGeneratedQuestion(RagGeneratedQuestionResponse question) {
        List<String> choices = question.getChoices().stream()
                .map(this::cleanMultipleChoiceOption)
                .toList();
        String correctAnswer = cleanMultipleChoiceOption(question.getCorrectAnswer());
        String conceptTag = isUsableShortAnswerConcept(question.getConceptTag())
                ? cleanShortAnswerConcept(question.getConceptTag())
                : inferConceptTag(question.getQuestion(), question.getSourceEvidence(), question.getModelAnswer());
        String modelAnswer = choices.stream()
                .filter(choice -> choice.equals(correctAnswer))
                .findFirst()
                .orElse(correctAnswer);
        return new RagGeneratedQuestionResponse(
                question.getOrder(),
                "multiple_choice",
                normalizeWhitespace(question.getQuestion()),
                choices,
                correctAnswer,
                modelAnswer,
                defaultValue(question.getExplanation(), "정답은 문서 근거와 가장 직접적으로 일치하는 설명입니다."),
                defaultValue(question.getSourceEvidence(), modelAnswer),
                defaultValue(question.getDifficulty(), "medium"),
                conceptTag,
                defaultValue(question.getUnderstandingLevel(), "CONCEPT_DISTINCTION")
        );
    }

    private boolean isHighQualityOxQuestion(RagGeneratedQuestionResponse question) {
        if (question.getChoices() == null
                || question.getChoices().size() != 2
                || !"O".equals(question.getChoices().get(0))
                || !"X".equals(question.getChoices().get(1))) {
            return false;
        }
        String answer = normalizeOxAnswer(question.getCorrectAnswer());
        if (!"O".equals(answer) && !"X".equals(answer)) {
            return false;
        }
        String statement = extractOxStatement(question.getQuestion());
        if (isInvalidOxStatement(statement) || isBrokenGeneratedText(statement)) {
            return false;
        }
        return statement.length() >= 15
                && !looksLikeQuestionStem(statement)
                && statement.matches(".*(다|니다|한다|된다|있다|없다|이다|아니다)\\.?$");
    }

    private RagGeneratedQuestionResponse normalizeOxGeneratedQuestion(RagGeneratedQuestionResponse question) {
        String statement = extractOxStatement(question.getQuestion());
        String answer = normalizeOxAnswer(question.getCorrectAnswer());
        String conceptTag = isUsableShortAnswerConcept(question.getConceptTag())
                ? cleanShortAnswerConcept(question.getConceptTag())
                : inferConceptTag(statement, question.getSourceEvidence(), question.getModelAnswer());
        return new RagGeneratedQuestionResponse(
                question.getOrder(),
                "ox",
                "다음 설명이 맞으면 O, 틀리면 X를 고르세요.\n\"" + ensurePeriod(statement) + "\"",
                List.of("O", "X"),
                answer,
                ensurePeriod(statement),
                defaultValue(question.getExplanation(), answer + "가 정답입니다."),
                defaultValue(question.getSourceEvidence(), ensurePeriod(statement)),
                defaultValue(question.getDifficulty(), "medium"),
                conceptTag,
                defaultValue(question.getUnderstandingLevel(), "CONCEPT_UNDERSTANDING")
        );
    }

    private String cleanMultipleChoiceOption(String value) {
        return normalizeWhitespace(value)
                .replaceAll("^[A-Da-d]\\s*[.)．:]\\s*", "")
                .replaceAll("^[,.;:，、]\\s*", "")
                .trim();
    }

    private String choiceFingerprint(String value) {
        return normalizeWhitespace(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]+", "")
                .trim();
    }

    private boolean looksLikeQuestionStem(String value) {
        String normalized = normalizeWhitespace(value);
        return normalized.endsWith("?")
                || normalized.endsWith("？")
                || normalized.contains("무엇")
                || normalized.contains("고르")
                || normalized.contains("선택")
                || normalized.contains("설명하세요")
                || normalized.contains("맞는")
                || normalized.contains("알맞은");
    }

    private String extractOxStatement(String question) {
        String normalized = normalizeWhitespace(question);
        Matcher quoted = Pattern.compile("\"([^\"]+)\"").matcher(normalized);
        if (quoted.find()) {
            return stripLeadingOxMarker(quoted.group(1)).trim();
        }
        return stripLeadingOxMarker(normalized)
                .replaceAll("^다음 설명이 맞으면 O, 틀리면 X를 고르세요\\.?", "")
                .replaceAll("^다음 문장이 문서 내용과 일치하면 O, 일치하지 않으면 X를 고르세요\\.?", "")
                .trim();
    }

    private String ensurePeriod(String value) {
        String normalized = normalizeWhitespace(value).replaceAll("[?？]+$", "").trim();
        return normalized.matches(".*[.!。]$") ? normalized : normalized + ".";
    }

    private boolean hasDanglingCommaFragment(String value) {
        String normalized = normalizeWhitespace(value);
        int comma = Math.max(normalized.lastIndexOf(','), normalized.lastIndexOf('，'));
        if (comma < 0) {
            return false;
        }
        String tail = normalized.substring(comma + 1).trim();
        return tail.length() < 12
                || !tail.matches(".*(다|니다|요|함|한다|된다|있다|없다|[.!?。])$");
    }

    private List<ConceptEvidence> buildConceptPool(
            List<ConceptEvidence> conceptEvidence,
            List<String> evidenceSentences,
            List<RagGeneratedQuestionResponse> existingQuestions
    ) {
        List<ConceptEvidence> pool = conceptEvidence.isEmpty()
                ? evidenceSentences.stream()
                .filter(sentence -> !isBrokenGeneratedText(sentence))
                .map(sentence -> new ConceptEvidence(extractLeadingConcept(sentence), sentence))
                .filter(evidence -> isUsableConcept(evidence.concept()))
                .toList()
                : conceptEvidence.stream()
                .map(evidence -> new ConceptEvidence(normalizeConcept(evidence.concept()), evidence.explanation()))
                .filter(evidence -> isUsableConcept(evidence.concept()))
                .filter(evidence -> !isBrokenGeneratedText(evidence.explanation()))
                .distinct()
                .toList();
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
            if ("multiple_choice".equals(selectedType)) {
                base = buildMultipleChoiceQuestion(order, evidence, pool);
            } else if ("ox".equals(selectedType)) {
                base = buildOxQuestion(order, evidence, pool);
            } else {
                base = buildTypedCoverageQuestion(order, selectedType, evidence, understandingLevel);
            }
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
            return buildOxQuestion(order, evidence, pool);
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

            return buildSafeShortAnswerQuestion(
                    question.getOrder(),
                    question.getQuestion(),
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
                String normalizedOxAnswer = normalizeOxAnswer(correctAnswer);
                correctAnswer = normalizedOxAnswer.isBlank() ? "O" : normalizedOxAnswer;
                question = buildOxQuestionText(
                        question,
                        selectOxStatementSource(question, modelAnswer, explanation, sourceEvidence, correctAnswer)
                );
            }


            if ("short_answer".equals(type)) {
                choices = List.of();
                String resolvedConceptTag = conceptTag.isBlank() ? inferConceptTag(question, sourceEvidence, modelAnswer) : conceptTag;
                return buildSafeShortAnswerQuestion(
                        order,
                        question,
                        modelAnswer,
                        explanation,
                        sourceEvidence,
                        difficulty,
                        resolvedConceptTag,
                        normalizeUnderstandingLevel(understandingLevel, type, question)
                );
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
        return selectOxStatementSource(question, modelAnswer, explanation, sourceEvidence, "");
    }

    private String selectOxStatementSource(String question, String modelAnswer, String explanation, String sourceEvidence, String correctAnswer) {
        if ("X".equals(normalizeOxAnswer(correctAnswer))) {
            String questionStatement = extractOxStatement(question);
            if (!isInvalidOxStatement(questionStatement)) {
                return questionStatement;
            }
        }

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
        String concept = cleanShortAnswerConcept(evidence.concept());
        String answer = buildShortAnswerAnswer(concept, evidence.explanation(), "application");
        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                concept + " 개념을 실제 예시나 상황에 어떻게 적용할 수 있는지 설명하세요.",
                List.of(),
                answer,
                answer,
                answer,
                evidence.explanation(),
                "medium",
                concept,
                "CONCEPT_APPLICATION"
        );
    }

    private RagGeneratedQuestionResponse buildSafeShortAnswerQuestion(
            int order,
            String question,
            String modelAnswer,
            String explanation,
            String sourceEvidence,
            String difficulty,
            String conceptTag,
            String understandingLevel
    ) {
        String rawQuestion = normalizeWhitespace(question);
        String rawAnswerSource = firstNonBlank(modelAnswer, sourceEvidence, explanation);
        String concept = resolveShortAnswerConcept(rawQuestion, sourceEvidence, rawAnswerSource, conceptTag);
        String alignedConcept = resolveAnswerFocusedConcept(concept, rawQuestion, rawAnswerSource);
        String mode = isApplicationQuestion(rawQuestion) ? "application" : isExampleQuestion(rawQuestion) ? "example" : "concept";

        String answer = isValidAnswerForAlignedShortQuestion(rawQuestion, rawAnswerSource, alignedConcept)
                ? normalizeWhitespace(rawAnswerSource)
                : buildShortAnswerAnswer(alignedConcept, rawAnswerSource, mode);
        String safeQuestion = isQuestionAnswerAligned(rawQuestion, answer, alignedConcept, mode)
                ? rawQuestion
                : buildAnswerAlignedShortAnswerQuestion(rawQuestion, alignedConcept, answer, mode);
        String safeExplanation = isUsefulShortExplanation(explanation, alignedConcept)
                ? normalizeWhitespace(explanation)
                : buildShortAnswerExplanation(alignedConcept, safeQuestion, answer);
        String safeSourceEvidence = sourceEvidence == null || sourceEvidence.isBlank()
                ? answer
                : normalizeWhitespace(sourceEvidence);

        return new RagGeneratedQuestionResponse(
                order,
                "short_answer",
                safeQuestion,
                List.of(),
                answer,
                answer,
                safeExplanation,
                safeSourceEvidence,
                difficulty.isBlank() ? "medium" : difficulty,
                alignedConcept,
                understandingLevel.isBlank() ? ("application".equals(mode) ? "CONCEPT_APPLICATION" : "CONCEPT_UNDERSTANDING") : understandingLevel
        );
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalizeWhitespace(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String resolveAnswerFocusedConcept(String concept, String question, String answerSource) {
        List<String> commandTerms = extractCommandTerms(answerSource);
        List<String> questionCommandTerms = extractCommandTerms(question);
        if (commandTerms.size() >= 3 && questionCommandTerms.size() < commandTerms.size()) {
            return formatCommandConcept(commandTerms);
        }

        String answerConcept = cleanShortAnswerConcept(inferConceptTag(answerSource, answerSource, question));
        if (isUsableShortAnswerConcept(answerConcept) && !isLikelySameConcept(concept, answerConcept)) {
            String compactQuestion = normalizeWhitespace(question).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            String compactConcept = normalizeWhitespace(concept).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (compactQuestion.contains(compactConcept) && !answerDefinesConcept(answerSource, concept)) {
                return answerConcept;
            }
        }
        return cleanShortAnswerConcept(concept);
    }

    private boolean isQuestionAnswerAligned(String question, String answer, String concept, String mode) {
        String normalizedQuestion = normalizeWhitespace(question);
        if (!isValidShortAnswerQuestion(normalizedQuestion, concept, mode)) {
            return false;
        }

        List<String> answerCommands = extractCommandTerms(answer);
        if (answerCommands.size() >= 3) {
            String compactQuestion = normalizedQuestion.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            boolean mentionsCommands = answerCommands.stream()
                    .allMatch(term -> compactQuestion.contains(term.toLowerCase(Locale.ROOT)));
            return mentionsCommands && (normalizedQuestion.contains("차이")
                    || normalizedQuestion.contains("기능")
                    || normalizedQuestion.contains("역할")
                    || normalizedQuestion.contains("설명"));
        }

        if (asksForDefinition(normalizedQuestion)) {
            return answerDefinesConcept(answer, concept);
        }
        return true;
    }

    private String buildAnswerAlignedShortAnswerQuestion(String originalQuestion, String concept, String answer, String mode) {
        List<String> commandTerms = extractCommandTerms(answer);
        if (commandTerms.size() >= 3) {
            String commandConcept = formatCommandConcept(commandTerms);
            if (containsViContext(originalQuestion, answer) && !commandConcept.toLowerCase(Locale.ROOT).startsWith("vi ")) {
                commandConcept = "vi " + commandConcept;
            }
            return commandConcept + "의 기능과 차이를 설명하세요.";
        }

        if ("example".equals(mode)) {
            return concept + "의 예시를 들고 그 이유를 설명하세요.";
        }
        if ("application".equals(mode)) {
            return concept + " 개념을 실제 예시나 상황에 어떻게 적용할 수 있는지 설명하세요.";
        }
        if (answerDefinesConcept(answer, concept)) {
            return concept + "가 무엇인지 정의와 핵심 특징을 포함해 설명하세요.";
        }
        return concept + "에 대해 문서에서 설명한 핵심 역할과 특징을 설명하세요.";
    }

    private boolean isValidAnswerForAlignedShortQuestion(String question, String answer, String concept) {
        String normalizedAnswer = normalizeWhitespace(answer);
        if (normalizedAnswer.length() < 25 || isBrokenGeneratedText(normalizedAnswer)) {
            return false;
        }
        List<String> commandTerms = extractCommandTerms(normalizedAnswer);
        if (commandTerms.size() >= 3) {
            return commandTerms.stream().allMatch(term -> normalizedAnswer.contains(term));
        }
        return isValidShortAnswer(question, normalizedAnswer, concept);
    }

    private boolean isUsefulShortExplanation(String explanation, String concept) {
        String normalized = normalizeWhitespace(explanation);
        if (normalized.length() < 20 || isBrokenGeneratedText(normalized)) {
            return false;
        }
        if (normalized.equalsIgnoreCase(concept)) {
            return false;
        }
        return true;
    }

    private String buildShortAnswerExplanation(String concept, String question, String answer) {
        List<String> commandTerms = extractCommandTerms(answer);
        if (commandTerms.size() >= 3) {
            return "이 문제는 " + formatTermList(commandTerms) + " 명령어의 기능 차이를 구분할 수 있는지 확인합니다.";
        }
        if (asksForDefinition(question)) {
            return "이 문제는 " + concept + "의 정의와 핵심 특징을 이해했는지 확인합니다.";
        }
        return "이 문제는 " + concept + "에 대한 문서의 핵심 설명을 이해했는지 확인합니다.";
    }

    private boolean asksForDefinition(String question) {
        String normalized = normalizeWhitespace(question);
        return normalized.contains("무엇인지")
                || normalized.contains("정의")
                || normalized.contains("개념")
                || normalized.contains("핵심 특징");
    }

    private boolean answerDefinesConcept(String answer, String concept) {
        String normalizedAnswer = normalizeWhitespace(answer);
        String normalizedConcept = cleanShortAnswerConcept(concept);
        String compactAnswer = normalizedAnswer.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String compactConcept = normalizedConcept.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (compactConcept.isBlank() || !compactAnswer.contains(compactConcept)) {
            return false;
        }
        int conceptIndex = compactAnswer.indexOf(compactConcept);
        return conceptIndex >= 0 && conceptIndex <= 25
                && normalizedAnswer.matches(".*(이다|입니다|의미|말한다|사용되는|수행하는|역할|특징).*\\.?");
    }

    private boolean isLikelySameConcept(String left, String right) {
        String compactLeft = normalizeWhitespace(left).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String compactRight = normalizeWhitespace(right).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compactLeft.equals(compactRight)
                || compactLeft.contains(compactRight)
                || compactRight.contains(compactLeft);
    }

    private List<String> extractCommandTerms(String text) {
        String normalized = normalizeWhitespace(text);
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9가-힣])([A-Za-z]{1,4}|[가-힣A-Za-z0-9]{1,12})\\s*명령어").matcher(normalized);
        List<String> terms = new ArrayList<>();
        while (matcher.find()) {
            String term = matcher.group(1).trim();
            if (!term.isBlank() && terms.stream().noneMatch(existing -> existing.equals(term))) {
                terms.add(term);
            }
        }
        if (terms.size() >= 4 && terms.stream().anyMatch(term -> term.equalsIgnoreCase("vi"))) {
            terms = terms.stream()
                    .filter(term -> !term.equalsIgnoreCase("vi"))
                    .toList();
        }
        return terms;
    }

    private String formatCommandConcept(List<String> commandTerms) {
        List<String> terms = commandTerms == null ? List.of() : commandTerms.stream()
                .filter(term -> term != null && !term.isBlank())
                .distinct()
                .toList();
        if (isInputModeCommandSet(terms)) {
            return "입력 모드 전환 명령어";
        }
        return formatTermList(terms) + " 명령어";
    }

    private boolean isInputModeCommandSet(List<String> terms) {
        if (terms == null || terms.size() < 3) {
            return false;
        }
        Set<String> normalizedTerms = terms.stream()
                .map(term -> term == null ? "" : term.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return normalizedTerms.contains("i")
                && normalizedTerms.contains("a")
                && normalizedTerms.contains("o");
    }

    private String formatTermList(List<String> terms) {
        return terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private boolean containsViContext(String question, String answer) {
        String combined = normalizeWhitespace(question + " " + answer).toLowerCase(Locale.ROOT);
        return combined.contains("vi") || combined.contains("vim");
    }

    private String buildShortAnswerQuestionText(String concept, String mode) {
        String naturalConcept = naturalizeConceptLabel(concept);
        if (naturalConcept.contains("명령어")) {
            return naturalConcept + "의 기능과 차이를 설명하세요.";
        }
        return switch (mode) {
            case "example" -> naturalConcept + "의 예시를 들고 그 이유를 설명하세요.";
            case "application" -> naturalConcept + " 개념을 실제 예시나 상황에 어떻게 적용할 수 있는지 설명하세요.";
            default -> naturalConcept + "의 정의와 핵심 특징을 설명하세요.";
        };
    }

    private String resolveShortAnswerConcept(String question, String sourceEvidence, String modelAnswer, String conceptTag) {
        String candidate = cleanShortAnswerConcept(conceptTag);
        if (!isUsableShortAnswerConcept(candidate)) {
            candidate = cleanShortAnswerConcept(inferConceptTag(sourceEvidence, modelAnswer, question));
        }
        if (!isUsableShortAnswerConcept(candidate)) {
            candidate = "핵심 개념";
        }
        return candidate;
    }

    private String buildShortAnswerAnswer(String concept, String rawExplanation, String mode) {
        concept = naturalizeConceptLabel(concept);
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
        String cleaned = normalizeWhitespace(rawExplanation)
                .replaceAll("(?i)" + Pattern.quote(concept), "")
                .replaceAll("다음과 같음.*$", "")
                .replaceAll("아래와 같음.*$", "")
                .replaceAll("주요 역할은.*$", "")
                .replaceAll("로의 주요 역할", "핵심 역할")
                .trim();
        if (cleaned.length() < 12 || cleaned.matches(".*(은|는|이|가|을|를|의|로|으로)$")) {
            if (normalizeWhitespace(concept).toLowerCase(Locale.ROOT).contains("kernel")
                    || normalizeWhitespace(concept).contains("커널")) {
                return "운영체제의 핵심 부분으로서 프로세스, 메모리, 파일 같은 하드웨어 자원 접근을 중재하고 관리한다";
            }
            return "문서에서 설명한 핵심 역할을 수행하는 개념이다";
        }
        return cleaned.replaceAll("[.?!。]+$", "").trim();
    }

    private boolean isValidShortAnswerQuestion(String question, String concept, String mode) {
        String normalizedQuestion = normalizeWhitespace(question);
        String compactQuestion = normalizedQuestion.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String compactConcept = concept.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return !normalizedQuestion.isBlank()
                && normalizedQuestion.length() >= 18
                && !startsWithBrokenKoreanFragment(normalizedQuestion)
                && !endsWithBrokenKoreanFragment(normalizedQuestion)
                && !isQuestionMissingPredicate(normalizedQuestion)
                && isUsableShortAnswerConcept(concept)
                && !compactConcept.isBlank()
                && compactQuestion.contains(compactConcept)
                && (!"application".equals(mode) || isApplicationQuestion(normalizedQuestion));
    }

    private boolean isValidShortAnswer(String question, String answer, String concept) {
        String normalizedAnswer = normalizeWhitespace(answer);
        if (normalizedAnswer.length() < 25) {
            return false;
        }
        String compactAnswer = normalizedAnswer.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        String compactConcept = normalizeWhitespace(concept).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!compactConcept.isBlank() && !compactAnswer.contains(compactConcept)) {
            return false;
        }
        if (normalizedAnswer.matches(".*(다음과 같음|아래와 같음|주요 역할은|역할은|특징은|종류는)\\s*[.。]?$")
                || normalizedAnswer.matches(".*(은|는|이|가|을|를|의|로|으로|와|과|및)\\s*$")
                || normalizedAnswer.matches(".*(은\\s+[^.?!]{0,30}\\s+은|는\\s+[^.?!]{0,30}\\s+는).*")
                || normalizedAnswer.contains("로의 주요 역할")) {
            return false;
        }
        return !isApplicationQuestion(question) || hasConcreteSituation(normalizedAnswer);
    }

    private boolean isApplicationQuestion(String question) {
        String normalized = normalizeWhitespace(question).toLowerCase(Locale.ROOT);
        return normalized.contains("적용") || normalized.contains("상황") || normalized.contains("실제") || normalized.contains("application");
    }

    private boolean isExampleQuestion(String question) {
        String normalized = normalizeWhitespace(question).toLowerCase(Locale.ROOT);
        return normalized.contains("예시") || normalized.contains("사례") || normalized.contains("example");
    }

    private boolean hasConcreteSituation(String answer) {
        String normalized = normalizeWhitespace(answer).toLowerCase(Locale.ROOT);
        return normalized.contains("예를 들어")
                || normalized.contains("상황")
                || normalized.contains("프로그램")
                || normalized.contains("파일")
                || normalized.contains("메모리")
                || normalized.contains("요청")
                || normalized.contains("program")
                || normalized.contains("file")
                || normalized.contains("memory")
                || normalized.contains("request");
    }

    private String cleanShortAnswerConcept(String concept) {
        String cleaned = normalizeWhitespace(concept)
                .replaceAll("[\\p{Punct}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (!cleaned.contains(" ")) {
            cleaned = normalizeSearchToken(cleaned);
        }
        cleaned = naturalizeConceptLabel(cleaned);
        return cleaned.isBlank() ? "핵심 개념" : cleaned;
    }

    private String naturalizeConceptLabel(String concept) {
        String label = normalizeWhitespace(concept)
                .replaceAll("개념의\\s*핵심.*$", "")
                .replaceAll("정의와\\s*핵심\\s*특징.*$", "")
                .replaceAll("무엇인지.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (label.isBlank()) {
            return "핵심 개념";
        }

        label = splitKoreanCompoundTerms(label);
        label = label.replaceAll("\\s+", " ").trim();

        String compact = label.replaceAll("\\s+", "");
        String lower = label.toLowerCase(Locale.ROOT);
        if (compact.matches("(?i).*vi.*명령어.*입력.*모드.*전환.*")
                || compact.matches("(?i).*vi.*입력.*모드.*전환.*명령어.*")) {
            return "vi 입력 모드 전환 명령어";
        }
        if (compact.matches(".*입력.*모드.*전환.*명령어.*")
                || compact.matches(".*명령어.*입력.*모드.*전환.*")) {
            return lower.contains("vi") ? "vi 입력 모드 전환 명령어" : "입력 모드 전환 명령어";
        }

        return label;
    }

    private String splitKoreanCompoundTerms(String value) {
        String result = normalizeWhitespace(value);
        List<String> terms = List.of(
                "입력", "출력", "모드", "전환", "명령어", "편집기", "커서", "위치", "새", "줄",
                "프로세스", "스레드", "스케줄링", "메모리", "가상", "페이지", "페이징", "파일", "시스템",
                "데이터베이스", "정규화", "트랜잭션", "인덱스", "기본키", "외래키", "조인",
                "알고리즘", "정렬", "탐색", "그래프", "재귀", "복잡도",
                "함수", "클래스", "객체", "상속", "인터페이스", "예외", "처리",
                "네트워크", "프로토콜", "라우팅", "소켓", "패킷"
        );

        for (String term : terms) {
            result = result.replaceAll("(?<!\\s)" + Pattern.quote(term), " " + term);
            result = result.replaceAll(Pattern.quote(term) + "(?!\\s)", term + " ");
        }

        result = result.replaceAll("(?i)\\bvi\\s+명령어\\s+", "vi ");
        result = result.replaceAll("(?i)\\bvim\\s+명령어\\s+", "vim ");
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    private boolean isUsableShortAnswerConcept(String concept) {
        String cleaned = cleanShortAnswerConcept(concept);
        if (!isUsableConcept(cleaned)) {
            return false;
        }
        if (cleaned.matches("^(은|는|이|가|을|를|의|로|으로|와|과|및|또는|그리고).*$")) {
            return false;
        }
        String compact = cleaned.replaceAll("\\s+", "");
        return !Set.of(
                "관리", "구분", "설명", "적용", "예시", "상황", "개념", "핵심", "역할",
                "특징", "의미", "주요", "문제", "정답", "핵심개념"
        ).contains(compact);
    }

    private boolean startsWithBrokenKoreanFragment(String question) {
        return question.matches("^(은|는|이|가|을|를|의|로|으로|와|과|및|또는|그리고)\\s*.*");
    }

    private boolean endsWithBrokenKoreanFragment(String question) {
        return question.matches(".*(은|는|이|가|을|를|의|로|으로|와|과|및)\\s*[?？.]?$");
    }

    private boolean isQuestionMissingPredicate(String question) {
        return !question.endsWith("?")
                && !question.endsWith("？")
                && !question.endsWith(".")
                && !question.contains("설명하세요")
                && !question.contains("비교하세요")
                && !question.contains("적용")
                && !question.contains("예시");
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

    private String completeEvidenceDescription(String concept, String rawExplanation) {
        String cleanedConcept = cleanShortAnswerConcept(concept);
        String description = normalizeWhitespace(rawExplanation)
                .replaceAll("^[,.;:，、]\\s*", "")
                .replaceAll("(?i)^" + Pattern.quote(cleanedConcept) + "\\s*(은|는|이|가|:|-)?\\s*", "")
                .replaceAll("은\\(는\\)", "은")
                .replaceAll("이\\(가\\)", "이")
                .replaceAll("을\\(를\\)", "을")
                .trim();

        description = trimDanglingCommaFragment(description);
        if (isBrokenGeneratedText(description)
                || description.length() < 12
                || description.matches(".*(하며|하고|하거나|그리고|또는|및|,|，)\\s*$")) {
            if (cleanedConcept.contains("커널") || cleanedConcept.toLowerCase(Locale.ROOT).contains("kernel")) {
                description = "운영체제의 핵심 소프트웨어로서 CPU, 메모리, 파일 입출력 같은 하드웨어 자원 접근을 중재하고 관리한다";
            } else if (cleanedConcept.contains("프로세스")) {
                description = "실행 중인 프로그램의 상태와 자원 사용을 관리하고 필요한 실행 흐름을 제어한다";
            } else {
                description = "문서에서 설명한 핵심 역할과 특징을 가진 개념이다";
            }
        }

        description = description.replaceAll("[.?!。]+$", "").trim();
        if (!description.matches(".*(다|니다|요|함|한다|된다|있다|없다)$")) {
            description = description + " 역할을 한다";
        }
        return cleanedConcept + subjectParticle(cleanedConcept) + " " + description + ".";
    }

    private String trimDanglingCommaFragment(String value) {
        String normalized = normalizeWhitespace(value);
        while (true) {
            int comma = Math.max(normalized.lastIndexOf(','), normalized.lastIndexOf('，'));
            if (comma < 0) {
                return normalized;
            }
            String tail = normalized.substring(comma + 1).trim();
            if (tail.length() >= 12 && tail.matches(".*(다|니다|요|함|한다|된다|있다|없다|[.!?。])$")) {
                return normalized;
            }
            normalized = normalized.substring(0, comma).trim();
        }
    }

    private RagGeneratedQuestionResponse buildOxQuestion(int order, ConceptEvidence evidence, List<ConceptEvidence> pool) {
        String naturalEvidenceConcept = cleanShortAnswerConcept(evidence.concept());
        final ConceptEvidence naturalEvidence = new ConceptEvidence(naturalEvidenceConcept, evidence.explanation());
        ConceptEvidence distractor = pool.stream()
                .filter(candidate -> !candidate.concept().equals(naturalEvidence.concept()))
                .findFirst()
                .orElse(null);
        boolean buildFalseStatement = distractor != null && order % 2 == 0;
        String statement = buildFalseStatement
                ? completeEvidenceDescription(naturalEvidence.concept(), distractor.explanation())
                : completeEvidenceDescription(naturalEvidence.concept(), naturalEvidence.explanation());
        String correctAnswer = buildFalseStatement ? "X" : "O";
        String explanation = buildFalseStatement
                ? "문서 근거에서 " + naturalEvidence.concept() + " 개념은 다음과 같이 설명됩니다: " + completeEvidenceDescription(naturalEvidence.concept(), naturalEvidence.explanation())
                : "문서에서 " + naturalEvidence.concept() + "에 대한 설명과 일치하므로 O가 정답입니다.";

        return new RagGeneratedQuestionResponse(
                order,
                "ox",
                "다음 설명이 맞으면 O, 틀리면 X를 고르세요.\n\"" + statement + "\"",
                List.of("O", "X"),
                correctAnswer,
                completeEvidenceDescription(naturalEvidence.concept(), naturalEvidence.explanation()),
                explanation,
                completeEvidenceDescription(naturalEvidence.concept(), naturalEvidence.explanation()),
                "easy",
                naturalEvidence.concept(),
                "CONCEPT_UNDERSTANDING"
        );
    }

    private RagGeneratedQuestionResponse buildMultipleChoiceQuestion(int order, ConceptEvidence target, List<ConceptEvidence> pool) {
        List<String> distractors = pool.stream()
                .filter(candidate -> !candidate.concept().equals(target.concept()))
                .map(candidate -> completeEvidenceDescription(candidate.concept(), candidate.explanation()))
                .filter(explanation -> !explanation.equals(completeEvidenceDescription(target.concept(), target.explanation())))
                .distinct()
                .limit(3)
                .collect(Collectors.toCollection(ArrayList::new));

        while (distractors.size() < 3) {
            distractors.add(buildFallbackDistractor(target, distractors.size()));
        }

        String naturalTargetConcept = cleanShortAnswerConcept(target.concept());
        String correctChoice = completeEvidenceDescription(naturalTargetConcept, target.explanation());
        List<String> choices = new ArrayList<>();
        choices.add(correctChoice);
        choices.addAll(distractors.subList(0, 3));
        Collections.shuffle(choices);

        return new RagGeneratedQuestionResponse(
                order,
                "multiple_choice",
                naturalTargetConcept + "에 대한 설명으로 가장 알맞은 것은 무엇인가요?",
                choices,
                correctChoice,
                correctChoice,
                "정답은 문서에서 " + naturalTargetConcept + " 개념을 직접 설명한 문장입니다.",
                correctChoice,
                "medium",
                naturalTargetConcept,
                "CONCEPT_DISTINCTION"
        );
    }

    private RagGeneratedQuestionResponse buildFallbackQuestion(int order, String selectedType, String fallback) {
        String concept = inferConceptTag(fallback, fallback, fallback);
        ConceptEvidence evidence = new ConceptEvidence(concept, fallback);
        if ("multiple_choice".equals(selectedType)) {
            return buildMultipleChoiceQuestion(order, evidence, List.of(evidence));
        }

        if ("ox".equals(selectedType)) {
            return buildOxQuestion(order, evidence, List.of(evidence));
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
            case 0 -> target.concept() + "은 사용자 화면의 색상과 글꼴을 설정하는 기능만 담당한다.";
            case 1 -> target.concept() + "은 프로그램 실행과 무관한 문서 저장 형식만을 의미한다.";
            default -> target.concept() + "은 하드웨어나 소프트웨어 자원 관리와 관계없이 결과 화면을 꾸미는 역할만 수행한다.";
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

    private String formatChunkSource(DocumentChunk chunk) {
        Map<String, String> metadata = parseChunkMetadata(chunk.getMetadata());
        String chunkType = metadata.getOrDefault("chunkType", "TEXT");
        String sourceType = metadata.getOrDefault("sourceType", "PDF_TEXT");
        String pageNumber = metadata.get("pageNumber");
        String pageSuffix = pageNumber == null || pageNumber.isBlank() ? "" : ", page " + pageNumber;
        return chunk.getDocument().getTitle()
                + " [chunk " + chunk.getChunkIndex()
                + ", " + chunkType
                + ", " + sourceType
                + pageSuffix
                + "]";
    }

    private Map<String, String> parseChunkMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : metadata.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length == 2 && !keyValue[0].isBlank()) {
                values.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
        return values;
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

    private record DocumentUploadAnalysis(
            String inferredSubject,
            String inferredUnit,
            List<String> recommendedTags,
            String documentDifficulty,
            String confidence
    ) {
    }
}
