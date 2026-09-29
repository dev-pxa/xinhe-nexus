package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ManagementDtos.PositionPermissions;
import com.xinhe.nexus.management.service.ManagementDtos.PositionSave;
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
@RequestMapping("/api/system/positions")
public class PositionController {
    private final ManagementSystemService service;

    public PositionController(ManagementSystemService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long orgUnitId
    ) {
        return ApiResponse.success(service.listPositions(user, companyId, orgUnitId));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestBody PositionSave body
    ) {
        return ApiResponse.success(service.createPosition(user, companyId, body));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody PositionSave body
    ) {
        service.updatePosition(user, id, body);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/delete-impact")
    public ApiResponse<Map<String, Object>> deleteImpact(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        return ApiResponse.success(service.positionDeleteImpact(user, id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        service.deletePosition(user, id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/permissions")
    public ApiResponse<List<Long>> permissions(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id
    ) {
        return ApiResponse.success(service.positionPermissions(user, id));
    }

    @PutMapping("/{id}/permissions")
    public ApiResponse<Void> setPermissions(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id,
            @RequestBody PositionPermissions body
    ) {
        service.replacePositionPermissions(user, id, body);
        return ApiResponse.success(null);
    }
}
