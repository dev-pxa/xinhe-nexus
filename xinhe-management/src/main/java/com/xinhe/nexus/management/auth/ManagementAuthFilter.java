package com.xinhe.nexus.management.auth;

import com.xinhe.nexus.common.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ManagementAuthFilter extends OncePerRequestFilter {
    private final ManagementAuthService authService;
    private final ObjectMapper objectMapper;

    public ManagementAuthFilter(ManagementAuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/logout")
                || path.equals("/api/system/bootstrap")
                || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ManagementUser currentUser = authService.currentUser(request);
        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.failure(401, "未登录或登录已过期"));
            return;
        }
        request.setAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE, currentUser);
        chain.doFilter(request, response);
    }
}
