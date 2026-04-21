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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class SessionQuizSchemaInitializer {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Bean
    public CommandLineRunner ensureSessionQuizSchema() {
        return args -> {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                if (!tableExists(metaData, "session_quiz")) {
                    return;
                }

                Set<String> columns = readColumns(metaData, "session_quiz");

                addColumnIfMissing(columns, "quiz_set_id", "alter table session_quiz add column quiz_set_id varchar(64)");
                addColumnIfMissing(columns, "quiz_set_title", "alter table session_quiz add column quiz_set_title varchar(255)");
                addColumnIfMissing(columns, "question_order", "alter table session_quiz add column question_order integer");

                if (isPostgreSql(metaData)) {
                    jdbcTemplate.execute("alter table session_quiz alter column question type text");
                    jdbcTemplate.execute("alter table session_quiz alter column choices_json type text");
                    jdbcTemplate.execute("alter table session_quiz alter column correct_answer type text");
                    jdbcTemplate.execute("alter table session_quiz alter column model_answer type text");
                    jdbcTemplate.execute("alter table session_quiz alter column explanation type text");
                    jdbcTemplate.execute("alter table session_quiz alter column source_evidence type text");
                }

                jdbcTemplate.update("update session_quiz set quiz_set_id = concat('legacy-', id) where quiz_set_id is null");
                jdbcTemplate.update("update session_quiz set quiz_set_title = 'Legacy Quiz Set' where quiz_set_title is null");
                jdbcTemplate.update("update session_quiz set question_order = coalesce(question_order, id) where question_order is null");
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to initialize session_quiz schema", exception);
            }
        };
    }

    private void addColumnIfMissing(Set<String> columns, String columnName, String sql) {
        if (columns.contains(columnName)) {
            return;
        }
        jdbcTemplate.execute(sql);
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

    private boolean isPostgreSql(DatabaseMetaData metaData) throws SQLException {
        return metaData.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
    }
}
