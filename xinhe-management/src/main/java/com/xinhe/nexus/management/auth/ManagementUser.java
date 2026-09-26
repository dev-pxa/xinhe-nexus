package com.xinhe.nexus.management.auth;

import java.util.List;

public record ManagementUser(
        long id,
        long tenantId,
        String username,
        String name,
        List<String> roles,
        List<String> permissions
) {
    public static ManagementUser superAdmin(String username) {
        return new ManagementUser(
                1L,
                1L,
                username,
                "超级管理员",
                List.of("PLATFORM_ADMIN"),
                List.of("*")
        );
    }
}
