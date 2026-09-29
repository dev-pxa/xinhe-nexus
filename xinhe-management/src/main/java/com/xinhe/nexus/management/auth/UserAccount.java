package com.xinhe.nexus.management.auth;

import java.time.LocalDateTime;

public record UserAccount(
        long id,
        String username,
        String passwordHash,
        String displayName,
        String userType,
        String status,
        boolean mustChangePassword,
        int loginFailedCount,
        LocalDateTime lockedUntil,
        long authVersion
) {
}
