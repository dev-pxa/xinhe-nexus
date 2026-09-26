package com.xinhe.nexus.management.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ManagementAuthServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ManagementAuthService authService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        authService = new ManagementAuthService(redisTemplate, "admin", "admin");
    }

    @Test
    void loginCreatesHttpOnlyCookieAndRedisSession() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ManagementUser user = authService.login(
                new LoginRequest("admin", "admin", false),
                new MockHttpServletRequest(),
                response
        );

        assertNotNull(user);
        assertEquals("admin", user.username());
        assertEquals("PLATFORM_ADMIN", user.roles().get(0));
        assertEquals("*", user.permissions().get(0));
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains(AuthConstants.COOKIE_NAME + "="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        verify(valueOperations).set(
                startsWith(AuthConstants.SESSION_KEY_PREFIX),
                eq("admin"),
                eq(Duration.ofHours(12))
        );
    }

    @Test
    void loginRejectsInvalidCredentials() {
        ManagementUser user = authService.login(
                new LoginRequest("admin", "wrong", false),
                new MockHttpServletRequest(),
                new MockHttpServletResponse()
        );

        assertNull(user);
        verifyNoInteractions(valueOperations);
    }

    @Test
    void currentUserUsesCookieBackedRedisSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthConstants.COOKIE_NAME, "session-token"));
        when(valueOperations.get(anyString())).thenReturn("admin");

        ManagementUser user = authService.currentUser(request);

        assertNotNull(user);
        assertEquals("超级管理员", user.name());
        verify(valueOperations).get(AuthConstants.SESSION_KEY_PREFIX + "session-token");
    }

    @Test
    void logoutDeletesSessionAndExpiresCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthConstants.COOKIE_NAME, "session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        authService.logout(request, response);

        verify(redisTemplate).delete(AuthConstants.SESSION_KEY_PREFIX + "session-token");
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Max-Age=0"));
    }
}
