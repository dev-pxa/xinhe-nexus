package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ManagementDtos.CompanyCreate;
import com.xinhe.nexus.management.service.ManagementDtos.CompanyUpdate;
import com.xinhe.nexus.management.service.ManagementSystemService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/companies")
public class CompanyController {
    private final ManagementSystemService service;

    public CompanyController(ManagementSystemService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user
    ) {
        return ApiResponse.success(service.listCompanies(user));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestBody CompanyCreate body
    ) {
        return ApiResponse.success(service.createCompany(user, body));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody CompanyUpdate body
    ) {
        service.updateCompany(user, id, body);
        return ApiResponse.success(null);
    }
}
