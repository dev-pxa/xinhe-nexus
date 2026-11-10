package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.EmployeeOnboardingService;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingInvitationCreate;
import com.xinhe.nexus.management.service.ManagementDtos.OnboardingSubmit;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr/onboarding")
public class EmployeeOnboardingController {
    private final EmployeeOnboardingService service;
    public EmployeeOnboardingController(EmployeeOnboardingService service) { this.service = service; }

    @PostMapping("/invitations")
    public ApiResponse<Map<String,Object>> create(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody OnboardingInvitationCreate body) { return ApiResponse.success(service.createInvitation(user, body)); }
    @GetMapping("/invitations")
    public ApiResponse<List<Map<String,Object>>> list(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestParam(required=false) String status) { return ApiResponse.success(service.listInvitations(user, status)); }
    @GetMapping("/employees/{id}")
    public ApiResponse<Map<String,Object>> detail(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.detail(user, id)); }
    @PostMapping("/invitations/{id}/revoke")
    public ApiResponse<Void> revoke(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { service.revoke(user, id); return ApiResponse.success(null); }
    @PostMapping("/invitations/{id}/regenerate")
    public ApiResponse<Map<String,Object>> regenerate(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.regenerate(user, id)); }
    @GetMapping("/invitations/{id}/link")
    public ApiResponse<Map<String,Object>> link(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.currentLink(user, id)); }
}
