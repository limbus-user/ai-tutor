package com.gyeongtaekim.ai_tutor.controller;

import com.gyeongtaekim.ai_tutor.dto.AuthResponse;
import com.gyeongtaekim.ai_tutor.dto.LoginRequest;
import com.gyeongtaekim.ai_tutor.dto.SignupRequest;
import com.gyeongtaekim.ai_tutor.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest request) {
        return ResponseEntity.ok(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}