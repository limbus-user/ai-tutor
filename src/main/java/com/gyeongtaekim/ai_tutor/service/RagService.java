package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RagService {

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.embedding.model:text-embedding-3-small}")
    private String openAiEmbeddingModelName;

    private final RagDocumentRepository ragDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    private final InMemoryEmbeddingStore<DocumentChunk> embeddingStore = new InMemoryEmbeddingStore<>();
    private volatile boolean embeddingsInitialized = false;

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
        List<DocumentChunk> allChunks = documentChunkRepository.findAll();
        if (allChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "검색 가능한 문서가 없습니다. 먼저 /api/rag/upload 로 PDF를 적재해 주세요.",
                    List.of()
            );
        }

        List<DocumentChunk> topChunks = retrieveRelevantChunks(query, allChunks);
        if (topChunks.isEmpty()) {
            return new RagQueryResponse(
                    query,
                    "질문과 직접적으로 일치하는 문서 근거를 찾지 못했습니다. 질문을 더 구체적으로 입력해 주세요.",
                    List.of()
            );
        }

        String answer = topChunks.stream()
                .map(chunk -> chunk.getChunkText().trim())
                .collect(Collectors.joining("\n\n"));
        List<String> sources = topChunks.stream()
                .map(chunk -> chunk.getDocument().getTitle() + " [chunk " + chunk.getChunkIndex() + "]")
                .distinct()
                .toList();

        return new RagQueryResponse(query, answer, sources);
    }

    public String generateQuestions(String fileName) {
        RagDocument document = ragDocumentRepository.findByStoredFileName(fileName)
                .orElseGet(() -> loadLegacyDocument(fileName));

        List<DocumentChunk> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        String preview = buildPreview(document.getExtractedText());
        String generatedQuestions = buildQuestionSet(document.getExtractedText(), chunks);

        return "File: " + fileName
                + "\n\n[Extracted Text Preview]\n"
                + preview
                + "\n\n[Generated Questions]\n"
                + generatedQuestions;
    }

    private List<DocumentChunk> retrieveRelevantChunks(String query, List<DocumentChunk> allChunks) {
        List<DocumentChunk> embeddedMatches = retrieveWithEmbeddings(query);
        if (!embeddedMatches.isEmpty()) {
            return embeddedMatches;
        }

        return allChunks.stream()
                .map(chunk -> new ScoredChunk(chunk, scoreChunk(query, chunk.getChunkText())))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt(ScoredChunk::score).reversed())
                .limit(3)
                .map(ScoredChunk::chunk)
                .toList();
    }

    private List<DocumentChunk> retrieveWithEmbeddings(String query) {
        if (!embeddingEnabled()) {
            return List.of();
        }

        initializeEmbeddingsIfNeeded();

        try {
            EmbeddingModel embeddingModel = createEmbeddingModel();
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest request = new EmbeddingSearchRequest(queryEmbedding, 3, 0.55, null);

            return embeddingStore.search(request).matches().stream()
                    .sorted(Comparator.comparingDouble(EmbeddingMatch<DocumentChunk>::score).reversed())
                    .map(EmbeddingMatch::embedded)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            return List.of();
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

    private String buildPreview(String text) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isBlank()) {
            return "(No text extracted from PDF)";
        }

        int previewLength = Math.min(280, normalized.length());
        return normalized.substring(0, previewLength) + (normalized.length() > previewLength ? "..." : "");
    }

    private String buildQuestionSet(String text, List<DocumentChunk> chunks) {
        String normalized = normalizeWhitespace(text);
        if (normalized.isBlank()) {
            return "1. PDF에서 텍스트를 추출하지 못했습니다. 다른 PDF로 다시 시도해 주세요.";
        }

        List<String> candidateSentences = chunks.stream()
                .map(DocumentChunk::getChunkText)
                .flatMap(chunk -> List.of(chunk.split("(?<=[.!?])\\s+")).stream())
                .map(this::normalizeWhitespace)
                .filter(sentence -> sentence.length() >= 20)
                .distinct()
                .limit(4)
                .toList();

        List<String> questions = new ArrayList<>();
        questions.add("1. 이 문서의 핵심 주제를 한 문장으로 요약하세요.");

        int index = 2;
        for (String sentence : candidateSentences) {
            questions.add(index + ". 다음 내용을 설명하세요: " + sentence);
            index++;
            if (questions.size() == 5) {
                break;
            }
        }

        Set<String> keywords = extractKeywords(normalized);
        for (String keyword : keywords) {
            if (questions.size() == 5) {
                break;
            }
            questions.add((questions.size() + 1) + ". 문서에서 언급된 '" + keyword + "'의 의미를 설명하세요.");
        }

        while (questions.size() < 5) {
            questions.add((questions.size() + 1) + ". 문서 내용 중 중요한 개념 하나를 골라 설명하세요.");
        }

        return String.join("\n", questions);
    }

    private Set<String> extractKeywords(String text) {
        return List.of(text.split("\\s+")).stream()
                .map(token -> token.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}가-힣]", ""))
                .filter(token -> token.length() >= 3)
                .limit(10)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private int scoreChunk(String query, String chunkText) {
        String lowerChunk = chunkText.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : normalizeWhitespace(query).toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.length() < 2) {
                continue;
            }
            if (lowerChunk.contains(token)) {
                score += 2;
            }
        }
        return score;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String extractTextFromBytes(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private record ScoredChunk(DocumentChunk chunk, int score) {
    }
}
