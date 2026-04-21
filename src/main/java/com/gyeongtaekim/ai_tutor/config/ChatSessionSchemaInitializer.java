package com.gyeongtaekim.ai_tutor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@RequiredArgsConstructor
public class ChatSessionSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public CommandLineRunner ensureChatSessionSchema() {
        return args -> {
            jdbcTemplate.execute("alter table chat_session add column if not exists type varchar(20)");
            jdbcTemplate.update("update chat_session set type = 'STUDY' where type is null");
        };
    }
}
