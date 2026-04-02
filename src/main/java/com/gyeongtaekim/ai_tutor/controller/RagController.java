package com.gyeongtaekim.ai_tutor.controller;

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
    public ResponseEntity<String> uploadPdf(@RequestParam("file") MultipartFile file) {
        try {
            ragService.processPdf(file);
            return ResponseEntity.ok("PDF processed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error processing PDF: " + e.getMessage());
        }
    }

    @PostMapping("/query")
    public ResponseEntity<String> query(@RequestBody String query) {
        try {
            String response = ragService.query(query);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error querying: " + e.getMessage());
        }
    }

    @PostMapping("/generate-questions")
    public ResponseEntity<String> generateQuestions(@RequestParam("fileName") String fileName) {
        try {
            String questions = ragService.generateQuestions(fileName);
            return ResponseEntity.ok(questions);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error generating questions: " + e.getMessage());
        }
    }
}
