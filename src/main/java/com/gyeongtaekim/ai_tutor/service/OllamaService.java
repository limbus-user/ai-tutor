package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OllamaService {

    @Value("${ollama.enabled:false}")
    private boolean enabled;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.chat.model:qwen2.5:7b}")
    private String chatModel;

    @Value("${ollama.temperature:0.1}")
    private double temperature;

    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return enabled && chatModel != null && !chatModel.isBlank();
    }

    public String generate(String systemPrompt, String prompt) {
        if (!isEnabled()) {
            return null;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", chatModel);
        request.put("system", systemPrompt);
        request.put("prompt", prompt);
        request.put("stream", false);
        request.put("options", Map.of("temperature", temperature));

        return callGenerate(request);
    }

    public String generateJson(String systemPrompt, String prompt) {
        if (!isEnabled()) {
            return null;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", chatModel);
        request.put("system", systemPrompt);
        request.put("prompt", prompt);
        request.put("format", "json");
        request.put("stream", false);
        request.put("options", Map.of("temperature", temperature));

        return callGenerate(request);
    }

    private String callGenerate(Map<String, Object> request) {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .build();

            String responseBody = client.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = root.get("response");
            return response == null || response.isNull() ? null : response.asText().trim();
        } catch (Exception e) {
            return null;
        }
    }
}
