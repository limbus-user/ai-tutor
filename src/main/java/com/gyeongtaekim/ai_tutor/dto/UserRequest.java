package com.gyeongtaekim.ai_tutor.dto;

import lombok.Getter;

@Getter
public class UserRequest {
    private String email;
    private String password;
    private String name;
}