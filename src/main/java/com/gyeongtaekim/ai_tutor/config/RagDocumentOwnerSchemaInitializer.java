package com.gyeongtaekim.ai_tutor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class RagDocumentOwnerSchemaInitializer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public CommandLineRunner ensureRagDocumentOwnerSchema() {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                if (!tableExists(metaData, "rag_document")) {
                    return;
                }

                Set<String> columns = readColumns(metaData, "rag_document");
                if (!columns.contains("user_id")) {
                    jdbcTemplate.execute("alter table rag_document add column user_id bigint");
                }

                backfillOwnerFromAttachedSessions(metaData);
                backfillRemainingLegacyDocumentsToDemoUser(metaData);
                createOwnerIndexIfPossible();
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to initialize rag_document owner schema", exception);
            }
        };
    }

    private void backfillOwnerFromAttachedSessions(DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "chat_session_document") || !tableExists(metaData, "chat_session")) {
            return;
        }

        List<Map<String, Object>> inferredOwners = jdbcTemplate.queryForList("""
                select csd.document_id as document_id,
                       min(cs.user_id) as user_id,
                       count(distinct cs.user_id) as user_count
                from chat_session_document csd
                join chat_session cs on cs.id = csd.session_id
                group by csd.document_id
                """);

        for (Map<String, Object> row : inferredOwners) {
            Number userCount = (Number) row.get("user_count");
            if (userCount == null || userCount.longValue() != 1L) {
                continue;
            }
            Number documentId = (Number) row.get("document_id");
            Number userId = (Number) row.get("user_id");
            if (documentId == null || userId == null) {
                continue;
            }
            jdbcTemplate.update(
                    "update rag_document set user_id = ? where id = ? and user_id is null",
                    userId.longValue(),
                    documentId.longValue()
            );
        }
    }

    private void backfillRemainingLegacyDocumentsToDemoUser(DatabaseMetaData metaData) throws SQLException {
        if (!tableExists(metaData, "users")) {
            return;
        }

        List<Long> demoUserIds = jdbcTemplate.query(
                "select id from users where email = 'demo@example.com'",
                (rs, rowNum) -> rs.getLong("id")
        );
        if (demoUserIds.isEmpty()) {
            return;
        }

        jdbcTemplate.update(
                "update rag_document set user_id = ? where user_id is null",
                demoUserIds.get(0)
        );
    }

    private void createOwnerIndexIfPossible() {
        try {
            jdbcTemplate.execute("create index if not exists idx_rag_document_user_id on rag_document(user_id)");
        } catch (Exception ignored) {
            // Index creation is best-effort for databases that do not support IF NOT EXISTS.
        }
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

    private Set<String> readColumns(DatabaseMetaData metaData, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        try (ResultSet resultSet = metaData.getColumns(null, null, null, null)) {
            while (resultSet.next()) {
                String currentTable = resultSet.getString("TABLE_NAME");
                if (!tableName.equalsIgnoreCase(currentTable)) {
                    continue;
                }
                columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }
}
