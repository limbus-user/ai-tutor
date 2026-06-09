package com.gyeongtaekim.ai_tutor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

@Configuration
@RequiredArgsConstructor
public class PgVectorSchemaInitializer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public CommandLineRunner ensurePgVectorSchema() {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                if (!isPostgreSql(metaData) || !tableExists(metaData, "document_chunk")) {
                    return;
                }

                jdbcTemplate.execute("create extension if not exists vector");
                jdbcTemplate.execute("alter table document_chunk add column if not exists embedding vector(1536)");
                jdbcTemplate.execute("""
                        create index if not exists idx_document_chunk_embedding
                        on document_chunk
                        using ivfflat (embedding vector_cosine_ops)
                        with (lists = 100)
                        """);
            } catch (Exception ignored) {
                // pgvector is optional at runtime; RAG falls back to keyword/hybrid search if unavailable.
            }
        };
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String current = resultSet.getString("TABLE_NAME");
                if (tableName.equalsIgnoreCase(current)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPostgreSql(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
    }
}
