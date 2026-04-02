package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.RagDocumentUploadResponse;
import com.gyeongtaekim.ai_tutor.dto.RagQueryResponse;
import com.gyeongtaekim.ai_tutor.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;

    @PostMapping("/upload")
    public ResponseEntity<RagDocumentUploadResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "unitName", required = false) String unitName,
            @RequestParam(value = "trustLevel", required = false) String trustLevel
    ) throws Exception {
        return ResponseEntity.ok(ragService.processPdf(file, subject, unitName, trustLevel));
    }

    @PostMapping("/query")
    public ResponseEntity<RagQueryResponse> query(@RequestBody String query) {
        return ResponseEntity.ok(ragService.query(query));
    }

    @PostMapping("/generate-questions")
    public ResponseEntity<String> generateQuestions(@RequestParam("fileName") String fileName) throws Exception {
        String questions = ragService.generateQuestions(fileName);
        return ResponseEntity.ok(questions);
    }
}
