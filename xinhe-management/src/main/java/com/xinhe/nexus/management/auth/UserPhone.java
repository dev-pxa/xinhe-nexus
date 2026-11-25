package com.xinhe.nexus.management.auth;

import com.xinhe.nexus.management.service.ManagementException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** All account phone writes share a transaction lock, including writes for different companies. */
public final class UserPhone {
    private UserPhone() {}

    public static boolean isMobile(String value) {
        return value != null && value.matches("^1[3-9][0-9]{9}$");
    }

    public static String validate(JdbcTemplate db, String value, Long userId) {
        String phone = value == null || value.isBlank() ? null : value.strip();
        if (phone != null && !isMobile(phone)) {
            throw new IllegalArgumentException("手机号格式不正确，请填写11位大陆手机号");
        }
        if (phone == null) return null;
        db.queryForList("SELECT id FROM sys_user_phone_guard WHERE id=1 FOR UPDATE");
        var matches = db.queryForList("""
                SELECT id FROM sys_user
                WHERE phone=? AND deleted_at IS NULL AND (? IS NULL OR id<>?) FOR UPDATE
                """, phone, userId, userId);
        if (!matches.isEmpty()) {
            throw new ManagementException(HttpStatus.CONFLICT, "该手机号已被其他账号使用");
        }
        return phone;
    }
}
