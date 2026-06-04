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
public class PdfTextSchemaInitializer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public CommandLineRunner ensurePdfTextSchema() {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                if (!isPostgreSql(metaData)) {
                    return;
                }

                if (tableExists(metaData, "document_chunk")) {
                    jdbcTemplate.execute("alter table document_chunk alter column chunk_text type text");
                }
                if (tableExists(metaData, "rag_document")) {
                    jdbcTemplate.execute("alter table rag_document alter column extracted_text type text");
                }
                if (tableExists(metaData, "chat_message")) {
                    jdbcTemplate.execute("alter table chat_message alter column content type text");
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to initialize PDF text schema", exception);
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
