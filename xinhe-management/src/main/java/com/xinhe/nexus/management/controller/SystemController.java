package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    @GetMapping("/bootstrap")
    public ApiResponse<Map<String, Object>> bootstrap() {
        return ApiResponse.success(Map.of(
                "application", "芯河智能管理平台",
                "version", "0.1.0",
                "modules", List.of("CRM", "ERP", "FINANCE", "OA", "SERVICE", "AI", "REPORT", "SYSTEM")
        ));
    }
}

