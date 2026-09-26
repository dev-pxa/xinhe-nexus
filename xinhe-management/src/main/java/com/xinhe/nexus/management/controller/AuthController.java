package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.LoginRequest;
import com.xinhe.nexus.management.auth.ManagementAuthService;
import com.xinhe.nexus.management.auth.ManagementUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
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
    public ApiResponse<ManagementUser> login(
            @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        ManagementUser user = authService.login(loginRequest, request, response);
        if (user == null) {
            return new ApiResponse<>(401, "用户名或密码错误", null, null, Instant.now());
        }
        return ApiResponse.success(user);
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
}
