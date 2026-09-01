package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentSummaryResponse;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentTitleUpdateRequest;
import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
import com.gyeongtaekim.ai_tutor.dto.RagGeneratedQuestionsResponse;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.service.CurrentUserService;
import com.gyeongtaekim.ai_tutor.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private final CurrentUserService currentUserService;

    @GetMapping("/documents")
    public ResponseEntity<List<RagDocumentSummaryResponse>> getDocuments(
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.getDocuments(user));
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<UrlResource> downloadDocument(
            @PathVariable Long documentId,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) throws MalformedURLException {
        User user = currentUserService.resolveUser(authentication, userId);
        RagDocument document = ragService.getDocument(user, documentId);
        Path path = ragService.resolveStoredFilePath(user, documentId);
        UrlResource resource = new UrlResource(path.toUri());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.getTitle() + "\"")
                .body(resource);
    }

    @GetMapping("/documents/{documentId}/analysis")
    public ResponseEntity<RagDocumentUploadResponse> analyzeDocument(
            @PathVariable Long documentId,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.analyzeDocument(user, documentId));
    }

    @PatchMapping("/documents/{documentId}")
    public ResponseEntity<RagDocumentSummaryResponse> renameDocument(
            @PathVariable Long documentId,
            @RequestBody RagDocumentTitleUpdateRequest request,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.renameDocument(user, documentId, request.getTitle()));
    }



    @PatchMapping("/documents/{documentId}/metadata")
    public ResponseEntity<RagDocumentSummaryResponse> updateDocumentMetadata(
            @PathVariable Long documentId,
            @RequestBody RagDocumentMetadataUpdateRequest request,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.updateDocumentMetadata(
                user,
                documentId,
                request.getSubject(),
                request.getUnitName(),
                request.getTrustLevel()
        ));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long documentId,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        ragService.deleteDocument(user, documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/upload")
    public ResponseEntity<RagDocumentUploadResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "unitName", required = false) String unitName,
            @RequestParam(value = "trustLevel", required = false) String trustLevel,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) throws Exception {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.processPdf(user, file, subject, unitName, trustLevel));
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
    public ResponseEntity<RagQueryResponse> query(
            @RequestBody String query,
            Authentication authentication,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        User user = currentUserService.resolveUser(authentication, userId);
        return ResponseEntity.ok(ragService.query(user, query));
    }

    @PostMapping("/generate-questions")
    public ResponseEntity<RagGeneratedQuestionsResponse> generateQuestions(
            @RequestParam(value = "documentId", required = false) Long documentId,
            @RequestParam(value = "documentIds", required = false) List<Long> documentIds,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "type", required = false, defaultValue = "mixed") String type,
            @RequestParam(value = "count", required = false, defaultValue = "5") Integer count,
            @RequestParam(value = "userId", required = false) Long userId,
            Authentication authentication
    ) throws Exception {
        User user = currentUserService.resolveUser(authentication, userId);
        RagGeneratedQuestionsResponse questions = ragService.generateQuestions(user, documentId, documentIds, sessionId, fileName, type, count);
        return ResponseEntity.ok(questions);
    }
}
