package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.LoginRequest;
import com.xinhe.nexus.management.auth.ManagementAuthService;
import com.xinhe.nexus.management.auth.ManagementUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final ManagementAuthService authService;

    public AuthController(ManagementAuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<ManagementUser>> login(
            @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ManagementUser user = authService.login(loginRequest, request, response);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ApiResponse<>(401, "用户名或密码错误，或账号当前不可用", null, null, Instant.now()));
        }
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    @GetMapping("/current")
    public ApiResponse<ManagementUser> current(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser currentUser
    ) {
        return ApiResponse.success(currentUser);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return ApiResponse.success(null);
    }

    @PostMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser currentUser,
            @RequestBody ChangePasswordRequest body
    ) {
        try {
            if (!authService.changePassword(currentUser.id(), body.currentPassword(), body.newPassword())) {
                return ResponseEntity.badRequest().body(ApiResponse.failure(400, "当前密码不正确"));
            }
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(ApiResponse.failure(400, exception.getMessage()));
        }
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}
