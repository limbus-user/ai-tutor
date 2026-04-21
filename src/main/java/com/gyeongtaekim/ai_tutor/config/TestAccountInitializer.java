package com.gyeongtaekim.ai_tutor.config;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class TestAccountInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public CommandLineRunner seedAdminTestAccount() {
        return args -> {
            User user = userRepository.findByEmail("demo@example.com")
                    .orElseGet(() -> userRepository.save(new User(
                            "demo@example.com",
                            passwordEncoder.encode("secret123"),
                            "Demo Admin"
                    )));

            if (user.getRole() != User.Role.ADMIN) {
                user.promoteToAdmin();
                userRepository.save(user);
            }
        };
    }
}
