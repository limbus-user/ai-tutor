package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User resolveUser(Authentication authentication, Long fallbackUserId) {
        Optional<User> authenticatedUser = findAuthenticatedUser(authentication);
        if (authenticatedUser.isPresent()) {
            User user = authenticatedUser.get();
            if (fallbackUserId != null && !fallbackUserId.equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User id does not match authenticated user");
            }
            return user;
        }

        if (fallbackUserId == null) {
            return null;
        }

        return userRepository.findById(fallbackUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public Optional<User> findAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        String email = authentication.getName();
        if (email == null || email.isBlank() || "anonymousUser".equals(email)) {
            return Optional.empty();
        }

        return Optional.of(userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found")));
    }
}
