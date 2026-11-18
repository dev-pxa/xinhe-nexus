package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ManagementDtos.ResetPassword;
import com.xinhe.nexus.management.service.ManagementDtos.UserCreate;
import com.xinhe.nexus.management.service.ManagementDtos.UserPermissions;
import com.xinhe.nexus.management.service.ManagementDtos.UserPosition;
import com.xinhe.nexus.management.service.ManagementDtos.UserUpdate;
import com.xinhe.nexus.management.service.ManagementSystemService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/users")
public class UserController {
    private final ManagementSystemService service;

    public UserController(ManagementSystemService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) String keyword
    ) {
        return ApiResponse.success(service.listUsers(user, companyId, keyword));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestBody UserCreate body
    ) {
        return ApiResponse.success(service.createUser(user, body));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody UserUpdate body
    ) {
        service.updateUser(user, id, body);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestParam(required = false) Long companyId
    ) {
        service.deleteUser(user, id, companyId);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/position")
    public ApiResponse<Map<String, Object>> position(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        return ApiResponse.success(service.userPosition(user, id));
    }

    @PutMapping("/{id}/position")
    public ApiResponse<Void> setPosition(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestParam long companyId,
            @RequestBody UserPosition body
    ) {
        service.replaceUserPosition(user, companyId, id, body);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/permissions")
    public ApiResponse<Map<String, Object>> permissions(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        return ApiResponse.success(service.userPermissions(user, id));
    }

    @PutMapping("/{id}/permissions")
    public ApiResponse<Void> setPermissions(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody UserPermissions body
    ) {
        service.replaceUserPermissions(user, id, body);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody ResetPassword body
    ) {
        service.resetPassword(user, id, body);
        return ApiResponse.success(null);
    }
}
