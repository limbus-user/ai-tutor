package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuth2LoginService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User loadOrCreateUser(String registrationId, OAuth2User oauth2User) {
        OAuth2Profile profile = OAuth2Profile.from(registrationId, oauth2User.getAttributes());
        return userRepository.findByEmail(profile.email())
                .orElseGet(() -> userRepository.save(new User(
                        profile.email(),
                        passwordEncoder.encode(UUID.randomUUID().toString()),
                        profile.name()
                )));
    }

    private record OAuth2Profile(String email, String name) {
        static OAuth2Profile from(String registrationId, Map<String, Object> attributes) {
            if ("kakao".equals(registrationId)) {
                return fromKakao(attributes);
            }
            return fromDefault(attributes);
        }

        @SuppressWarnings("unchecked")
        private static OAuth2Profile fromKakao(Map<String, Object> attributes) {
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            Map<String, Object> profile = kakaoAccount == null
                    ? Map.of()
                    : (Map<String, Object>) kakaoAccount.getOrDefault("profile", Map.of());

            String email = stringValue(kakaoAccount == null ? null : kakaoAccount.get("email"));
            String name = stringValue(profile.get("nickname"));
            return validate(email, name);
        }

        private static OAuth2Profile fromDefault(Map<String, Object> attributes) {
            String email = stringValue(attributes.get("email"));
            String name = stringValue(attributes.get("name"));
            return validate(email, name);
        }

        private static OAuth2Profile validate(String email, String name) {
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("소셜 계정에서 이메일을 확인할 수 없습니다.");
            }
            String fallbackName = email.substring(0, email.indexOf("@"));
            return new OAuth2Profile(email, name == null || name.isBlank() ? fallbackName : name);
        }

        private static String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }
}
