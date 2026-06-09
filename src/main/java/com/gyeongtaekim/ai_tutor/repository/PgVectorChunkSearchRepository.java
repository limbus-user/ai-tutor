package com.gyeongtaekim.ai_tutor.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class PgVectorChunkSearchRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void updateEmbedding(Long chunkId, float[] embedding) {
        if (chunkId == null || embedding == null || embedding.length == 0) {
            return;
        }

        String sql = """
                update document_chunk
                set embedding = cast(:embedding as vector)
                where id = :chunkId
                """;

        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("embedding", toVectorLiteral(embedding))
                .addValue("chunkId", chunkId));
    }

    public List<VectorChunkMatch> searchSimilar(float[] queryEmbedding, Long documentId, List<Long> documentIds, int limit) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || limit <= 0) {
            return List.of();
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("queryEmbedding", toVectorLiteral(queryEmbedding))
                .addValue("limit", limit);

        StringBuilder sql = new StringBuilder("""
                select id, 1 - (embedding <=> cast(:queryEmbedding as vector)) as score
                from document_chunk
                where embedding is not null
                """);

        List<Long> filteredDocumentIds = normalizeDocumentIds(documentId, documentIds);
        if (!filteredDocumentIds.isEmpty()) {
            sql.append(" and document_id in (:documentIds)");
            parameters.addValue("documentIds", filteredDocumentIds);
        }

        sql.append("""
                order by embedding <=> cast(:queryEmbedding as vector)
                limit :limit
                """);

        return jdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> new VectorChunkMatch(
                rs.getLong("id"),
                Math.max(0.0, rs.getDouble("score"))
        ));
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

    private String toVectorLiteral(float[] embedding) {
        return "[" + java.util.stream.IntStream.range(0, embedding.length)
                .mapToObj(index -> Float.toString(embedding[index]).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(",")) + "]";
    }

    public record VectorChunkMatch(Long chunkId, double score) {
    }
}
