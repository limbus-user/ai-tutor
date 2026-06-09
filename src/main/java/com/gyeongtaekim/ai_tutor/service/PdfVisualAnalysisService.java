package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfVisualAnalysisService {

    private static final int RENDER_DPI = 130;

    @Value("${openai.api.key:}")
    private String openAiApiKey;

    @Value("${openai.vision.model:gpt-4o}")
    private String visionModel;

    @Value("${openai.vision.max-pages:5}")
    private int maxVisionPages;

    @Value("${openai.vision.enabled:true}")
    private boolean visionEnabled;

    private final ObjectMapper objectMapper;

    public List<VisualAnalysisResult> analyze(byte[] pdfBytes) {
        if (!isEnabled() || pdfBytes == null || pdfBytes.length == 0) {
            return List.of();
        }

        List<VisualAnalysisResult> results = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            PDFTextStripper textStripper = new PDFTextStripper();
            int pagesToInspect = Math.min(document.getNumberOfPages(), Math.max(0, maxVisionPages));

            for (int pageIndex = 0; pageIndex < pagesToInspect; pageIndex++) {
                PDPage page = document.getPage(pageIndex);
                textStripper.setStartPage(pageIndex + 1);
                textStripper.setEndPage(pageIndex + 1);
                String pageText = textStripper.getText(document);

                if (!shouldAnalyzePage(page, pageText)) {
                    continue;
                }

                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
                String imageDataUrl = toPngDataUrl(pageImage);
                VisualAnalysisResult result = analyzePageImage(imageDataUrl, pageIndex + 1);
                if (result != null && result.hasUsefulDescription()) {
                    results.add(result);
                }
            }
        } catch (Exception exception) {
            log.warn("PDF visual analysis skipped: {}", exception.getMessage());
            return List.of();
        }
        return results;
    }

    private boolean isEnabled() {
        return visionEnabled
                && openAiApiKey != null
                && !openAiApiKey.isBlank()
                && visionModel != null
                && !visionModel.isBlank();
    }

    private boolean shouldAnalyzePage(PDPage page, String pageText) {
        try {
            if (hasImageXObject(page.getResources())) {
                return true;
            }
        } catch (Exception ignored) {
            return true;
        }
        return looksLikeTablePage(pageText);
    }

    private boolean hasImageXObject(PDResources resources) throws java.io.IOException {
        if (resources == null) {
            return false;
        }
        for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject) {
                return true;
            }
            if (xObject instanceof PDFormXObject form && hasImageXObject(form.getResources())) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeTablePage(String pageText) {
        if (pageText == null || pageText.isBlank()) {
            return false;
        }
        String[] lines = pageText.split("\\R");
        int tableLikeLines = 0;
        for (String line : lines) {
            String normalized = line.trim();
            if (normalized.contains("|")
                    || normalized.contains("\t")
                    || normalized.matches(".*\\s{3,}.*\\s{3,}.*")
                    || normalized.matches(".*\\b\\d+(\\.\\d+)?\\b\\s+\\b\\d+(\\.\\d+)?\\b.*")) {
                tableLikeLines++;
            }
        }
        return tableLikeLines >= 3;
    }

    private String toPngDataUrl(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
        return "data:image/png;base64," + base64;
    }

    private VisualAnalysisResult analyzePageImage(String imageDataUrl, int pageNumber) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", visionModel);
            request.put("temperature", 0.1);
            request.put("response_format", Map.of("type", "json_object"));
            request.put("messages", List.of(
                    Map.of(
                            "role", "system",
                            "content", """
                                    You analyze only tables, images, charts, and diagrams from PDF pages.
                                    Return strict JSON only.
                                    Do not repeat ordinary body text. Extract only learning value from visual material.
                                    """
                    ),
                    Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of(
                                            "type", "text",
                                            "text", """
                                                    이 이미지는 PDF에서 추출한 표/그림/도식이 포함된 페이지입니다.
                                                    일반 본문 텍스트를 반복하지 말고, 표/이미지/도식에서 학습에 필요한 내용만 설명하세요.
                                                    표라면 행과 열의 의미를 보존하여 정리하세요.
                                                    그림이나 도식이라면 구성 요소, 화살표 관계, 핵심 개념을 설명하세요.
                                                    결과는 한국어 학습 자료용 설명문으로 작성하세요.
                                                    불확실하면 confidence를 낮게 표시하세요.

                                                    JSON 형식:
                                                    {"chunkType":"TABLE 또는 IMAGE 또는 DIAGRAM","title":"표/이미지 제목","description":"학습용 설명","summary":"핵심 요약","keywords":["키워드1","키워드2"],"confidence":"high 또는 medium 또는 low"}
                                                    """
                                    ),
                                    Map.of(
                                            "type", "image_url",
                                            "image_url", Map.of("url", imageDataUrl)
                                    )
                            )
                    )
            ));

            String responseBody = RestClient.builder()
                    .baseUrl("https://api.openai.com")
                    .build()
                    .post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return null;
            }

            JsonNode result = objectMapper.readTree(extractJsonObject(content.asText()));
            return new VisualAnalysisResult(
                    normalizeChunkType(result.path("chunkType").asText("VISUAL_SUMMARY")),
                    pageNumber,
                    result.path("title").asText("PDF visual material"),
                    result.path("description").asText(""),
                    result.path("summary").asText(""),
                    readKeywords(result.path("keywords")),
                    normalizeConfidence(result.path("confidence").asText("low"))
            );
        } catch (Exception exception) {
            log.warn("GPT Vision analysis failed for PDF page {}: {}", pageNumber, exception.getMessage());
            return null;
        }
    }

    private String extractJsonObject(String value) {
        String trimmed = value == null ? "" : value.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private List<String> readKeywords(JsonNode keywordsNode) {
        if (keywordsNode == null || !keywordsNode.isArray()) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode keyword : keywordsNode) {
            String value = keyword.asText("").trim();
            if (!value.isBlank()) {
                keywords.add(value);
            }
        }
        return keywords;
    }

    private String normalizeChunkType(String value) {
        String normalized = value == null ? "VISUAL_SUMMARY" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (List.of("TABLE", "IMAGE", "DIAGRAM", "VISUAL_SUMMARY").contains(normalized)) {
            return normalized;
        }
        return "VISUAL_SUMMARY";
    }

    private String normalizeConfidence(String value) {
        String normalized = value == null ? "low" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (List.of("high", "medium", "low").contains(normalized)) {
            return normalized;
        }
        return "low";
    }

    public record VisualAnalysisResult(
            String chunkType,
            int pageNumber,
            String title,
            String description,
            String summary,
            List<String> keywords,
            String confidence
    ) {
        public boolean hasUsefulDescription() {
            return (description != null && !description.isBlank()) || (summary != null && !summary.isBlank());
        }

        public String toChunkText() {
            String keywordText = keywords == null || keywords.isEmpty() ? "" : "\n키워드: " + String.join(", ", keywords);
            return """
                    [%s] %s
                    설명: %s
                    요약: %s%s
                    """.formatted(chunkType, title, description, summary, keywordText).trim();
        }
    }
}
