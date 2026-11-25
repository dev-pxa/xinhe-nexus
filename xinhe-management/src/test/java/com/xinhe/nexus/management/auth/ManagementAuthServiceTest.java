package com.xinhe.nexus.management.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

class ManagementAuthServiceTest {
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private UserAccountRepository accountRepository;
    private PasswordEncoder passwordEncoder;
    private ManagementAuthService authService;
    private UserAccount rootAccount;
    private ManagementUser rootUser;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        accountRepository = mock(UserAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        authService = new ManagementAuthService(redisTemplate, accountRepository, passwordEncoder);
        rootAccount = new UserAccount(
                1L, "root", "bcrypt-hash", "平台管理员", "PLATFORM_ADMIN",
                "ENABLED", true, 0, null, 3L);
        rootUser = new ManagementUser(
                1L, null, null, "root", "平台管理员",
                List.of("PLATFORM_ADMIN"), List.of("*"), true, 3L);
    }

    @Test
    void phoneLoginUsesTheSamePasswordAndIdentity() {
        when(accountRepository.findByPhone("13800138000")).thenReturn(rootAccount);
        when(passwordEncoder.matches("root", "bcrypt-hash")).thenReturn(true);
        when(accountRepository.toManagementUser(rootAccount)).thenReturn(rootUser);
        var result = authService.authenticate(new LoginRequest(" 13800138000 ", "root", false),
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertEquals(rootUser, result.user());
        verify(valueOperations).set(startsWith(AuthConstants.SESSION_KEY_PREFIX), eq("1:3"), eq(Duration.ofHours(12)));
    }

    @Test
    void missingOrAmbiguousPhoneCannotLogin() {
        var result = authService.authenticate(new LoginRequest("13800138000", "root", false),
                new MockHttpServletRequest(), new MockHttpServletResponse());
        assertNull(result.user());
        assertEquals("账号或密码错误", result.message());
        verifyNoInteractions(passwordEncoder, valueOperations);
    }

    @Test
    void loginCreatesHttpOnlyCookieAndVersionedRedisSession() {
        when(accountRepository.findByUsername("root")).thenReturn(rootAccount);
        when(passwordEncoder.matches("root", "bcrypt-hash")).thenReturn(true);
        when(accountRepository.toManagementUser(rootAccount)).thenReturn(rootUser);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ManagementUser user = authService.login(
                new LoginRequest("root", "root", false), new MockHttpServletRequest(), response);

        assertNotNull(user);
        assertEquals("root", user.username());
        assertEquals("PLATFORM_ADMIN", user.roles().get(0));
        String setCookie = response.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains(AuthConstants.COOKIE_NAME + "="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        verify(valueOperations).set(
                startsWith(AuthConstants.SESSION_KEY_PREFIX), eq("1:3"), eq(Duration.ofHours(12)));
        verify(accountRepository).recordLoginSuccess(1L);
    }

    @Test
    void loginRejectsInvalidPasswordAndRecordsFailure() {
        when(accountRepository.findByUsername("root")).thenReturn(rootAccount);
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);

        ManagementUser user = authService.login(
                new LoginRequest("root", "wrong", false),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertNull(user);
        verify(accountRepository).recordLoginFailure(1L);
        verifyNoInteractions(valueOperations);
    }

    @Test
    void currentUserRejectsSessionAfterAuthVersionChanges() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthConstants.COOKIE_NAME, "session-token"));
        when(valueOperations.get(anyString())).thenReturn("1:2");
        when(accountRepository.findById(1L)).thenReturn(rootAccount);

        assertNull(authService.currentUser(request));
        verify(redisTemplate).delete(AuthConstants.SESSION_KEY_PREFIX + "session-token");
    }

    @Test
    void currentUserLoadsDatabaseBackedIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthConstants.COOKIE_NAME, "session-token"));
        when(valueOperations.get(anyString())).thenReturn("1:3");
        when(accountRepository.findById(1L)).thenReturn(rootAccount);
        when(accountRepository.toManagementUser(rootAccount)).thenReturn(rootUser);

        ManagementUser user = authService.currentUser(request);

        assertNotNull(user);
        assertEquals("平台管理员", user.name());
    }

    @Test
    void changePasswordValidatesMinimumLength() {
        when(accountRepository.findById(1L)).thenReturn(rootAccount);
        when(passwordEncoder.matches("root", "bcrypt-hash")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> authService.changePassword(1L, "root", "short", "short"));
    }

    @Test
    void changePasswordRequiresMatchingConfirmation() {
        assertThrows(IllegalArgumentException.class,
                () -> authService.changePassword(1L, "root", "Abc123!", "Abc123?"));
        verifyNoInteractions(accountRepository);
    }

    @Test
    void passwordPolicyRequiresThreeAsciiCharacterCategories() {
        authService.validatePassword("xinhe123.");
        authService.validatePassword("Abc123");

        assertThrows(IllegalArgumentException.class, () -> authService.validatePassword("abcdef1"));
        assertThrows(IllegalArgumentException.class, () -> authService.validatePassword("Abc 123"));
        assertThrows(IllegalArgumentException.class, () -> authService.validatePassword("密码Abc123"));
    }

    @Test
    void logoutDeletesSessionAndExpiresCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthConstants.COOKIE_NAME, "session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        authService.logout(request, response);

        verify(redisTemplate).delete(AuthConstants.SESSION_KEY_PREFIX + "session-token");
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
    }
}
