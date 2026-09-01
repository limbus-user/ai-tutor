package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.DocumentChunk;
import com.gyeongtaekim.ai_tutor.domain.RagDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QdrantVectorStoreService {

    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${qdrant.collection:document_chunks}")
    private String collectionName;

    @Value("${qdrant.vector-size:1536}")
    private int vectorSize;

    private volatile boolean collectionEnsured = false;

    public synchronized void ensureCollection() {
        if (collectionEnsured) {
            return;
        }

        RestClient client = client();
        try {
            client.get()
                    .uri("/collections/{collection}", collectionName)
                    .retrieve()
                    .toBodilessEntity();
            collectionEnsured = true;
            return;
        } catch (RestClientException ignored) {
            // Create below. If Qdrant is down, the create call reports a clear error.
        }

        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", vectorSize,
                        "distance", "Cosine"
                )
        );

        try {
            client.put()
                    .uri("/collections/{collection}", collectionName)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            collectionEnsured = true;
        } catch (RestClientException exception) {
            throw qdrantUnavailable("Qdrant is not available. Start it with docker compose up -d qdrant.", exception);
        }
    }

    public void upsertChunkEmbedding(DocumentChunk chunk, float[] embedding) {
        if (chunk == null || chunk.getId() == null || embedding == null || embedding.length == 0) {
            return;
        }
        Long userId = chunk.getDocument() == null || chunk.getDocument().getUser() == null
                ? null
                : chunk.getDocument().getUser().getId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required to store chunk embeddings in Qdrant.");
        }
        ensureVectorSize(embedding);
        ensureCollection();

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", chunk.getId());
        point.put("vector", toVector(embedding));
        point.put("payload", buildPayload(chunk));

        Map<String, Object> body = Map.of("points", List.of(point));
        try {
            client().put()
                    .uri("/collections/{collection}/points?wait=true", collectionName)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw qdrantUnavailable("Failed to upsert chunk embedding to Qdrant.", exception);
        }
    }

    public List<VectorChunkMatch> searchSimilarChunks(
            float[] queryEmbedding,
            Long userId,
            Long documentId,
            List<Long> documentIds,
            int limit
    ) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || limit <= 0) {
            return List.of();
        }
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required for Qdrant vector search.");
        }
        ensureVectorSize(queryEmbedding);
        ensureCollection();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", toVector(queryEmbedding));
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("filter", buildFilter(userId, documentId, documentIds));

        try {
            Map<?, ?> response = client().post()
                    .uri("/collections/{collection}/points/search", collectionName)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return parseMatches(response);
        } catch (RestClientException exception) {
            throw qdrantUnavailable("Failed to search similar chunks in Qdrant.", exception);
        }
    }

    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        deleteByFilter(match("documentId", documentId), "Failed to delete document vectors from Qdrant.");
    }

    public void deleteByUserId(Long userId) {
        if (userId == null) {
            return;
        }
        deleteByFilter(match("userId", userId), "Failed to delete user vectors from Qdrant.");
    }

    private void deleteByFilter(Map<String, Object> condition, String errorMessage) {
        ensureCollection();
        Map<String, Object> body = Map.of(
                "filter", Map.of("must", List.of(condition))
        );
        try {
            client().post()
                    .uri("/collections/{collection}/points/delete?wait=true", collectionName)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw qdrantUnavailable(errorMessage, exception);
        }
    }

    private Map<String, Object> buildPayload(DocumentChunk chunk) {
        RagDocument document = chunk.getDocument();
        Long userId = document.getUser() == null ? null : document.getUser().getId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkId", chunk.getId());
        payload.put("userId", userId);
        payload.put("documentId", document.getId());
        payload.put("sessionId", null);
        payload.put("pageNumber", parsePageNumber(chunk.getMetadata()));
        payload.put("chunkIndex", chunk.getChunkIndex());
        return payload;
    }

    private Map<String, Object> buildFilter(Long userId, Long documentId, List<Long> documentIds) {
        List<Map<String, Object>> must = new ArrayList<>();
        if (userId != null) {
            must.add(match("userId", userId));
        }

        List<Long> selectedDocumentIds = normalizeDocumentIds(documentId, documentIds);
        if (selectedDocumentIds.size() == 1) {
            must.add(match("documentId", selectedDocumentIds.get(0)));
        } else if (!selectedDocumentIds.isEmpty()) {
            must.add(Map.of(
                    "key", "documentId",
                    "match", Map.of("any", selectedDocumentIds)
            ));
        }
        return Map.of("must", must);
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }

    private List<Long> normalizeDocumentIds(Long documentId, List<Long> documentIds) {
        if (documentIds != null && !documentIds.isEmpty()) {
            return documentIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }
        if (documentId != null) {
            return List.of(documentId);
        }
        return List.of();
    }

    private Integer parsePageNumber(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        for (String part : metadata.split(",")) {
            String[] tokens = part.trim().split("=", 2);
            if (tokens.length == 2 && "pageNumber".equals(tokens[0].trim())) {
                try {
                    return Integer.parseInt(tokens[1].trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private List<Float> toVector(float[] embedding) {
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return vector;
    }

    private List<VectorChunkMatch> parseMatches(Map<?, ?> response) {
        Object result = response == null ? null : response.get("result");
        if (!(result instanceof List<?> items)) {
            return List.of();
        }

        List<VectorChunkMatch> matches = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> match)) {
                continue;
            }
            Long chunkId = readChunkId(match);
            if (chunkId == null) {
                continue;
            }
            double score = readScore(match.get("score"));
            matches.add(new VectorChunkMatch(chunkId, score));
        }
        return matches;
    }

    private Long readChunkId(Map<?, ?> match) {
        Object payload = match.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            Long chunkId = asLong(payloadMap.get("chunkId"));
            if (chunkId != null) {
                return chunkId;
            }
        }
        return asLong(match.get("id"));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double readScore(Object value) {
        if (value instanceof Number number) {
            return Math.max(0.0, number.doubleValue());
        }
        return 0.0;
    }

    private void ensureVectorSize(float[] embedding) {
        if (embedding.length != vectorSize) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Embedding vector size " + embedding.length + " does not match Qdrant collection size " + vectorSize
            );
        }
    }

    private ResponseStatusException qdrantUnavailable(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(qdrantUrl)
                .build();
    }

    public record VectorChunkMatch(Long chunkId, double score) {
    }
}
