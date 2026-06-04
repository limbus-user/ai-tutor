package com.gyeongtaekim.ai_tutor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyeongtaekim.ai_tutor.dto.FeedbackRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) throws IOException {
        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        if (message.length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback message must be at least 5 characters");
        }

        Path logDir = Path.of("run-logs");
        Files.createDirectories(logDir);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submittedAt", LocalDateTime.now().toString());
        payload.put("email", sanitize(request.getEmail(), 160));
        payload.put("category", sanitize(request.getCategory(), 80));
        payload.put("message", sanitize(message, 2000));

        Files.writeString(
                logDir.resolve("feedback-submissions.log"),
                objectMapper.writeValueAsString(payload) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
        );

        return ResponseEntity.ok(Map.of("message", "Feedback received"));
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
