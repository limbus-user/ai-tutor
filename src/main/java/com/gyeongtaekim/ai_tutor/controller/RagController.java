package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentTitleUpdateRequest;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionsResponse;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentMetadataUpdateRequest;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @GetMapping("/documents")
    public ResponseEntity<List<RagDocumentSummaryResponse>> getDocuments() {
        return ResponseEntity.ok(ragService.getDocuments());
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<UrlResource> downloadDocument(@PathVariable Long documentId) throws MalformedURLException {
        RagDocument document = ragService.getDocument(documentId);
        Path path = ragService.resolveStoredFilePath(documentId);
        UrlResource resource = new UrlResource(path.toUri());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.getTitle() + "\"")
                .body(resource);
    }

    @GetMapping("/documents/{documentId}/analysis")
    public ResponseEntity<RagDocumentUploadResponse> analyzeDocument(@PathVariable Long documentId) {
        return ResponseEntity.ok(ragService.analyzeDocument(documentId));
    }

    @PatchMapping("/documents/{documentId}")
    public ResponseEntity<RagDocumentSummaryResponse> renameDocument(
            @PathVariable Long documentId,
            @RequestBody RagDocumentTitleUpdateRequest request
    ) {
        return ResponseEntity.ok(ragService.renameDocument(documentId, request.getTitle()));
    }



    @PatchMapping("/documents/{documentId}/metadata")
    public ResponseEntity<RagDocumentSummaryResponse> updateDocumentMetadata(
            @PathVariable Long documentId,
            @RequestBody RagDocumentMetadataUpdateRequest request
    ) {
        return ResponseEntity.ok(ragService.updateDocumentMetadata(
                documentId,
                request.getSubject(),
                request.getUnitName(),
                request.getTrustLevel()
        ));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable Long documentId) {
        ragService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/upload")
    public ResponseEntity<RagDocumentUploadResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "unitName", required = false) String unitName,
            @RequestParam(value = "trustLevel", required = false) String trustLevel
    ) throws Exception {
        return ResponseEntity.ok(ragService.processPdf(file, subject, unitName, trustLevel));
    }

    @PostMapping("/analyze-preview")
    public ResponseEntity<RagDocumentUploadResponse> analyzePdfPreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "unitName", required = false) String unitName,
            @RequestParam(value = "trustLevel", required = false) String trustLevel
    ) throws Exception {
        return ResponseEntity.ok(ragService.analyzePdfPreview(file, subject, unitName, trustLevel));
    }

    @PostMapping("/query")
    public ResponseEntity<RagQueryResponse> query(@RequestBody String query) {
        return ResponseEntity.ok(ragService.query(query));
    }

    @PostMapping("/generate-questions")
    public ResponseEntity<RagGeneratedQuestionsResponse> generateQuestions(
            @RequestParam(value = "documentId", required = false) Long documentId,
            @RequestParam(value = "documentIds", required = false) List<Long> documentIds,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "type", required = false, defaultValue = "mixed") String type,
            @RequestParam(value = "count", required = false, defaultValue = "5") Integer count
    ) throws Exception {
        RagGeneratedQuestionsResponse questions = ragService.generateQuestions(documentId, documentIds, sessionId, fileName, type, count);
        return ResponseEntity.ok(questions);
    }
}
