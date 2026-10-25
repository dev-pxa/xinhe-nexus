package com.xinhe.nexus.management.auth;

public record LoginRequest(String username, String password, Boolean remember) {
}
