package com.gyeongtaekim.ai_tutor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OllamaService {

    @Value("${ollama.enabled:false}")
    private boolean enabled;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.chat.model:qwen2.5:7b}")
    private String chatModel;

    @Value("${ollama.temperature:0.1}")
    private double temperature;

    @Value("${ollama.num-ctx:8192}")
    private int numCtx;

    @Value("${ollama.num-predict:900}")
    private int numPredict;

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
        request.put("options", Map.of(
                "temperature", temperature,
                "num_ctx", numCtx,
                "num_predict", Math.max(700, numPredict)
        ));

        return callGenerate(request);
    }

    public String generateJson(String systemPrompt, String prompt) {
        return generateJson(systemPrompt, prompt, numPredict);
    }

    public String generateJson(String systemPrompt, String prompt, int requestedNumPredict) {
        if (!isEnabled()) {
            return null;
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", chatModel);
        request.put("system", systemPrompt);
        request.put("prompt", prompt);
        request.put("format", "json");
        request.put("stream", false);
        request.put("options", Map.of(
                "temperature", temperature,
                "num_ctx", numCtx,
                "num_predict", Math.max(numPredict, requestedNumPredict)
        ));

        return callGenerate(request);
    }

    private String callGenerate(Map<String, Object> request) {
        try {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(180));

            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(requestFactory)
                    .build();

            byte[] requestBody = objectMapper.writeValueAsBytes(request);

            byte[] responseBytes = client.post()
                    .uri("/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);

            String responseBody = responseBytes == null ? null : new String(responseBytes, StandardCharsets.UTF_8);
            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = root.get("response");
            return response == null || response.isNull() ? null : response.asText().trim();
        } catch (Exception e) {
            log.warn("Ollama generation request failed: {}", e.getMessage());
            return null;
        }
    }
}
