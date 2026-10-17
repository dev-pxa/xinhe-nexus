package com.xinhe.nexus.management.auth;

public record PersonalProfile(
        String username,
        String displayName,
        String phone,
        String email
) {
}
