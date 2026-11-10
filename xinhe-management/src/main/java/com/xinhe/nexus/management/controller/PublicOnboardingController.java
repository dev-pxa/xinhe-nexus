package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.service.EmployeeOnboardingService;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingSubmit;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/onboarding/forms")
public class PublicOnboardingController {
    private final EmployeeOnboardingService service;
    public PublicOnboardingController(EmployeeOnboardingService service) { this.service = service; }
    @GetMapping("/{token}")
    public ApiResponse<Map<String,Object>> form(@PathVariable String token) { return ApiResponse.success(service.publicForm(token)); }
    @PostMapping("/{token}/submit")
    public ApiResponse<Map<String,Object>> submit(@PathVariable String token, @RequestBody OnboardingSubmit body) { return ApiResponse.success(service.submit(token, body)); }
}
