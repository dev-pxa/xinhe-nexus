package com.xinhe.nexus.management.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ManagementAuthService {
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(12);
    private static final Duration REMEMBERED_SESSION_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public ManagementAuthService(
            StringRedisTemplate redisTemplate,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.redisTemplate = redisTemplate;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public ManagementUser login(LoginRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        return authenticate(request, servletRequest, response).user();
    }

    public LoginResult authenticate(LoginRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
        if (request == null || request.username() == null || request.password() == null) {
            return new LoginResult(null, "请输入用户名和密码");
        }
        String username = request.username().trim();
        if (username.isBlank()) return new LoginResult(null, "请输入用户名");
        if (request.password().isBlank()) return new LoginResult(null, "请输入密码");
        UserAccount account = userAccountRepository.findByUsername(username);
        if (account == null) {
            return new LoginResult(null, "用户名或密码错误");
        }
        if (!"ENABLED".equals(account.status())) {
            return new LoginResult(null, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.password(), account.passwordHash())) {
            userAccountRepository.recordLoginFailure(account.id());
            return new LoginResult(null, "用户名或密码错误");
        }
        ManagementUser user = userAccountRepository.toManagementUser(account);
        if (user == null) {
            return new LoginResult(null, "账号信息异常，请联系管理员");
        }
        Duration ttl = Boolean.TRUE.equals(request.remember()) ? REMEMBERED_SESSION_TTL : DEFAULT_SESSION_TTL;
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                AuthConstants.SESSION_KEY_PREFIX + token,
                account.id() + ":" + account.authVersion(), ttl);
        userAccountRepository.recordLoginSuccess(account.id());
        response.addHeader(HttpHeaders.SET_COOKIE,
                sessionCookie(token, ttl, Boolean.TRUE.equals(request.remember()), servletRequest.isSecure()).toString());
        return new LoginResult(user, null);
    }

    public ManagementUser currentUser(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isBlank()) {
            return null;
        }
        String session = redisTemplate.opsForValue().get(AuthConstants.SESSION_KEY_PREFIX + token);
        if (session == null) {
            return null;
        }
        String[] parts = session.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            UserAccount account = userAccountRepository.findById(Long.parseLong(parts[0]));
            if (account == null || !"ENABLED".equals(account.status())
                    || account.authVersion() != Long.parseLong(parts[1])) {
                redisTemplate.delete(AuthConstants.SESSION_KEY_PREFIX + token);
                return null;
            }
            return userAccountRepository.toManagementUser(account);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String token = resolveToken(request);
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(AuthConstants.SESSION_KEY_PREFIX + token);
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(AuthConstants.COOKIE_NAME, "")
                        .httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                        .path("/").maxAge(Duration.ZERO).build().toString());
    }

    public PersonalProfile getProfile(long userId) {
        return userAccountRepository.findProfile(userId);
    }

    public ManagementUser updateProfile(long userId, String displayName, String phone, String email) {
        String normalizedName = requireText(displayName, "姓名", 64);
        String normalizedPhone = optionalText(phone, "手机号", 32);
        String normalizedEmail = optionalText(email, "邮箱", 128);
        if (normalizedEmail != null
                && !normalizedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        userAccountRepository.updateProfile(userId, normalizedName, normalizedPhone, normalizedEmail);
        UserAccount account = userAccountRepository.findById(userId);
        return account == null ? null : userAccountRepository.toManagementUser(account);
    }

    public boolean changePassword(
            long userId,
            String currentPassword,
            String newPassword,
            String confirmPassword
    ) {
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("两次输入的新密码不一致");
        }
        UserAccount account = userAccountRepository.findById(userId);
        if (account == null || currentPassword == null
                || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            return false;
        }
        PasswordPolicy.validate(newPassword);
        userAccountRepository.updatePassword(userId, passwordEncoder.encode(newPassword));
        return true;
    }

    public void validatePassword(String password) {
        PasswordPolicy.validate(password);
    }

    public record LoginResult(ManagementUser user, String message) {
    }

    private String requireText(String value, String label, int maxLength) {
        String normalized = optionalText(value, label, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return normalized;
    }

    private String optionalText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度不能超过" + maxLength + "位");
        }
        return normalized;
    }

    private ResponseCookie sessionCookie(String token, Duration ttl, boolean remember, boolean secure) {
        ResponseCookie.ResponseCookieBuilder cookie = ResponseCookie.from(AuthConstants.COOKIE_NAME, token)
                .httpOnly(true).secure(secure).sameSite("Lax").path("/");
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
                .map(Cookie::getValue).findFirst().orElse(null);
    }
}
