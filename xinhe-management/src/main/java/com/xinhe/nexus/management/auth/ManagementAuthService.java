package com.xinhe.nexus.management.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class ManagementAuthService {
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);
    private static final Duration REMEMBERED_SESSION_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final String adminUsername;
    private final String adminPassword;

    public ManagementAuthService(
            StringRedisTemplate redisTemplate,
            @Value("${xinhe.auth.super-admin.username:admin}") String adminUsername,
            @Value("${xinhe.auth.super-admin.password:admin}") String adminPassword
    ) {
        this.redisTemplate = redisTemplate;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    public ManagementUser login(LoginRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        if (request == null
                || !constantTimeEquals(adminUsername, request.username())
                || !constantTimeEquals(adminPassword, request.password())) {
            return null;
        }

        Duration ttl = request.remember() ? REMEMBERED_SESSION_TTL : DEFAULT_SESSION_TTL;
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(AuthConstants.SESSION_KEY_PREFIX + token, adminUsername, ttl);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                sessionCookie(token, ttl, request.remember(), servletRequest.isSecure()).toString()
        );
        return ManagementUser.superAdmin(adminUsername);
    }

    public ManagementUser currentUser(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        String username = redisTemplate.opsForValue().get(AuthConstants.SESSION_KEY_PREFIX + token);
        if (!adminUsername.equals(username)) {
            return null;
        }
        return ManagementUser.superAdmin(username);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = resolveToken(request);
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(AuthConstants.SESSION_KEY_PREFIX + token);
        }
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie.from(AuthConstants.COOKIE_NAME, "")
                        .httpOnly(true)
                        .secure(request.isSecure())
                        .sameSite("Lax")
                        .path("/")
                        .maxAge(Duration.ZERO)
                        .build()
                        .toString()
        );
    }

    private ResponseCookie sessionCookie(String token, Duration ttl, boolean remember, boolean secure) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(AuthConstants.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
        if (remember) {
            cookie.maxAge(ttl);
        }
        return cookie.build();
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> AuthConstants.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
