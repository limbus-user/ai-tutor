package com.gyeongtaekim.ai_tutor.config;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class TestAccountInitializer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CommandLineRunner seedAdminTestAccount() {
        return args -> {
            User user = userRepository.findByEmail("demo@example.com")
                    .orElseGet(() -> userRepository.save(new User(
                            "demo@example.com",
                            passwordEncoder.encode("secret123"),
                            "Demo Admin"
                    )));

            boolean changed = false;
            if (!passwordEncoder.matches("secret123", user.getPassword())) {
                user.changePassword(passwordEncoder.encode("secret123"));
                changed = true;
            }
            if (user.getRole() != User.Role.ADMIN) {
                user.promoteToAdmin();
                changed = true;
            }
            if (changed) {
                userRepository.save(user);
            }
        };
    }
}
