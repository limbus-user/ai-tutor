package com.gyeongtaekim.ai_tutor.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RagService {

    @Value("${openai.api.key}")
    private String openaiApiKey;

    private final EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore();

    // private final EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
    //         .apiKey(openaiApiKey)
    //         .modelName("text-embedding-ada-002")
    //         .build();

    // private final ChatLanguageModel chatModel = OpenAiChatModel.builder()
    //         .apiKey(openaiApiKey)
    //         .modelName("gpt-3.5-turbo")
    //         .build();

    // private final RagAssistant assistant = AiServices.builder(RagAssistant.class)
    //         .chatLanguageModel(chatModel)
    //         .contentRetriever(EmbeddingStoreContentRetriever.builder()
    //                 .embeddingStore(embeddingStore)
    //                 .embeddingModel(embeddingModel)
    //                 .maxResults(5)
    //                 .minScore(0.7)
    //                 .build())
    //         .build();

    public void processPdf(MultipartFile file) throws IOException {
        String text = extractTextFromPdf(file);
        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 0);
        List<TextSegment> segments = splitter.split(document);
        // embeddingStore.addAll(embeddingModel.embedAll(segments).content(), segments);
    }

    public String query(String query) {
        // Mock response for testing
        return "Mock response: " + query;
    }

    public String generateQuestions(String fileName) throws IOException {
        System.out.println("DEBUG: generateQuestions called with fileName: " + fileName);
        try {
            // 저장된 PDF 파일 읽기
            Path filePath = Paths.get("uploads", fileName);
            System.out.println("DEBUG: filePath: " + filePath.toAbsolutePath());
            if (!Files.exists(filePath)) {
                throw new IOException("File not found: " + fileName);
            }
            byte[] bytes = Files.readAllBytes(filePath);
            System.out.println("DEBUG: File read successfully, size: " + bytes.length);

            // 텍스트 추출
            String text = extractTextFromBytes(bytes);
            System.out.println("DEBUG: Text extracted, length: " + text.length());

            // 문서 처리 및 벡터화 (주석 처리된 부분은 실제로는 실행되지 않음)
            Document document = Document.from(text);
            DocumentSplitter splitter = DocumentSplitters.recursive(300, 0);
            List<TextSegment> segments = splitter.split(document);
            System.out.println("DEBUG: Document processed, segments: " + segments.size());

            // 문제 생성 쿼리 (Mock 응답)
            String query = "이 문서의 내용을 기반으로 5개의 객관식 문제를 생성해 주세요. 각 문제는 4개의 선택지와 정답을 포함하세요.";
            String result = "Mock generated questions for " + fileName + ": 1. What is AI? A) Artificial Intelligence B) Animal Instinct C) Automated Interface D) None - Answer: A 2. How does RAG work? A) Retrieval-Augmented Generation B) Random Access Generator C) Real-time Analysis Graph D) None - Answer: A";
            System.out.println("DEBUG: Returning result: " + result.substring(0, Math.min(100, result.length())));
            return result;
        } catch (Exception e) {
            // 오류 발생 시에도 Mock 응답 반환 (디버깅용)
            System.out.println("DEBUG: Exception occurred: " + e.getMessage());
            e.printStackTrace();
            String result = "Mock generated questions for " + fileName + " (with error: " + e.getMessage() + "): 1. What is AI? A) Artificial Intelligence B) Animal Instinct C) Automated Interface D) None - Answer: A";
            return result;
        }
    }

    private String extractTextFromPdf(MultipartFile file) throws IOException {
        byte[] bytes = file.getBytes();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String extractTextFromBytes(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    interface RagAssistant {
        String chat(String message);
    }
}
