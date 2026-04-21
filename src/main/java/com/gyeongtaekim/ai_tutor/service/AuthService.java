package com.gyeongtaekim.ai_tutor.service;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.dto.AuthResponse;
import com.gyeongtaekim.ai_tutor.dto.LoginRequest;
import com.gyeongtaekim.ai_tutor.dto.SignupRequest;
import com.gyeongtaekim.ai_tutor.repository.UserRepository;
import com.gyeongtaekim.ai_tutor.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    // 회원가입
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        User user = new User(
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getName()
        );

        userRepository.save(user);
        String token = jwtTokenProvider.generateToken(user.getEmail());

        return new AuthResponse(user.getId(), token, user.getEmail(), user.getName(), user.getRole().name());
    }

    // 로그인
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() ->
                        new IllegalArgumentException("이메일 또는 비밀번호가 틀렸습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 틀렸습니다.");
        }

        String token = jwtTokenProvider.generateToken(user.getEmail());
        return new AuthResponse(user.getId(), token, user.getEmail(), user.getName(), user.getRole().name());
    }
}
