package com.gyeongtaekim.ai_tutor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthResponse {
    private Long id;
    private String token;
    private String email;
    private String name;
    private String role;
}
