package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.UserRequest;
import com.gyeongtaekim.ai_tutor.dto.UserResponse;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponse createUser(UserRequest request) {
        User user = new User(
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getName()
        );
        return new UserResponse(userRepository.save(user));
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(UserResponse::new)
                .toList();
    }
}