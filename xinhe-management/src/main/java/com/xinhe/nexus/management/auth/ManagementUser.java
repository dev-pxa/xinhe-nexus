package com.xinhe.nexus.management.auth;

import java.util.List;

public record ManagementUser(
        long id,
        Long companyId,
        String companyName,
        String username,
        String name,
        List<String> roles,
        List<String> permissions,
        boolean mustChangePassword,
        long authVersion,
        boolean headquarters
) {
    /** 兼容既有服务测试与未显式区分总部的调用点。 */
    public ManagementUser(long id, Long companyId, String companyName, String username, String name,
                          List<String> roles, List<String> permissions, boolean mustChangePassword,
                          long authVersion) {
        this(id, companyId, companyName, username, name, roles, permissions, mustChangePassword, authVersion, false);
    }
}
