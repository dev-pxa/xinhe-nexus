package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ManagementDtos.OrgSave;
import com.xinhe.nexus.management.service.ManagementSystemService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/org-units")
public class OrganizationController {
    private final ManagementSystemService service;

    public OrganizationController(ManagementSystemService service) {
        this.service = service;
    }

    @GetMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> tree(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId
    ) {
        return ApiResponse.success(service.listOrganizations(user, companyId));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestBody OrgSave body
    ) {
        return ApiResponse.success(service.createOrganization(user, companyId, body));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody OrgSave body
    ) {
        service.updateOrganization(user, id, body);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/delete-impact")
    public ApiResponse<Map<String, Object>> deleteImpact(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        return ApiResponse.success(service.organizationDeleteImpact(user, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        service.deleteOrganization(user, id);
        return ApiResponse.success(null);
    }
}
