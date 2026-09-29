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
        long authVersion
) {
}
