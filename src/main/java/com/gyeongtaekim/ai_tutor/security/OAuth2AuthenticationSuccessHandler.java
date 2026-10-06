package com.gyeongtaekim.ai_tutor.security;

import com.gyeongtaekim.ai_tutor.domain.User;
import com.gyeongtaekim.ai_tutor.service.OAuth2LoginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuth2LoginService oauth2LoginService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        OAuth2User oauth2User = oauthToken.getPrincipal();
        User user = oauth2LoginService.loadOrCreateUser(oauthToken.getAuthorizedClientRegistrationId(), oauth2User);
        String token = jwtTokenProvider.generateToken(user.getEmail());

        String authJson = objectMapper.writeValueAsString(Map.of(
                "id", user.getId(),
                "token", token,
                "email", user.getEmail(),
                "name", user.getName(),
                "role", user.getRole().name()
        ));

        response.sendRedirect("/#oauth2=" + URLEncoder.encode(authJson, StandardCharsets.UTF_8));
    }
}
