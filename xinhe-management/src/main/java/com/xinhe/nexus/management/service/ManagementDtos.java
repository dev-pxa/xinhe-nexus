package com.xinhe.nexus.management.service;

import java.util.List;

public final class ManagementDtos {
    private ManagementDtos() {
    }

    public record CompanyCreate(
            String companyName, Long upstreamCompanyId, String contactName, String contactPhone
    ) {
    }

    public record CompanyUpdate(
            String companyName, String contactName, String contactPhone, String status, int version
    ) {
    }

    public record OrgSave(
            String orgName, Long parentId, Long managerUserId, String contactPhone,
            String description, String status, Integer version
    ) {
    }

    public record PositionSave(
            Long orgUnitId, String positionName, String responsibilities,
            String status, Integer version
    ) {
    }

    public record UserCreate(
            Long companyId, String username, String password, String displayName,
            String phone, String email, boolean companyAdmin, List<Long> positionIds
    ) {
    }

    public record UserUpdate(
            String displayName, String phone, String email, String status,
            Boolean companyAdmin, Integer version
    ) {
    }

    public record StatusUpdate(String status, int version) {
    }

    public record PositionPermissions(List<Long> permissionIds) {
    }

    public record UserPositions(List<Long> positionIds, Long primaryPositionId) {
    }

    public record ResetPassword(String newPassword) {
    }
}
