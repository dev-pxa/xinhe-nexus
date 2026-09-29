package com.xinhe.nexus.management.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UserAccount findByUsername(String username) {
        List<UserAccount> users = jdbcTemplate.query(
                """
                SELECT id, username, password_hash, display_name, user_type, status,
                       must_change_password, login_failed_count, locked_until, auth_version
                FROM sys_user WHERE username = ? AND deleted_at IS NULL
                """, this::mapAccount, username);
        return users.isEmpty() ? null : users.get(0);
    }

    public UserAccount findById(long id) {
        List<UserAccount> users = jdbcTemplate.query(
                """
                SELECT id, username, password_hash, display_name, user_type, status,
                       must_change_password, login_failed_count, locked_until, auth_version
                FROM sys_user WHERE id = ? AND deleted_at IS NULL
                """, this::mapAccount, id);
        return users.isEmpty() ? null : users.get(0);
    }

    public ManagementUser toManagementUser(UserAccount account) {
        if ("PLATFORM_ADMIN".equals(account.userType())) {
            return new ManagementUser(
                    account.id(), null, null, account.username(), account.displayName(),
                    List.of("PLATFORM_ADMIN"), List.of("*"),
                    account.mustChangePassword(), account.authVersion());
        }
        List<CompanyMembership> memberships = jdbcTemplate.query(
                """
                SELECT uc.company_id, c.company_name, uc.is_company_admin
                FROM sys_user_company uc
                JOIN sys_company c ON c.id = uc.company_id AND c.deleted_at IS NULL
                WHERE uc.user_id = ? AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                  AND c.status = 'ENABLED'
                ORDER BY uc.is_company_admin DESC, uc.id LIMIT 1
                """,
                (rs, rowNum) -> new CompanyMembership(
                        rs.getLong("company_id"), rs.getString("company_name"),
                        rs.getBoolean("is_company_admin")), account.id());
        if (memberships.isEmpty()) {
            return null;
        }
        CompanyMembership membership = memberships.get(0);
        List<String> roles = membership.companyAdmin()
                ? List.of("COMPANY_ADMIN") : List.of("COMPANY_USER");
        List<String> permissions = membership.companyAdmin()
                ? List.of("*")
                : jdbcTemplate.queryForList(
                        """
                        SELECT DISTINCT p.permission_code
                        FROM sys_user_position up
                        JOIN sys_position pos ON pos.id = up.position_id
                            AND pos.company_id = up.company_id
                            AND pos.status = 'ENABLED' AND pos.deleted_at IS NULL
                        JOIN sys_position_permission pp ON pp.position_id = pos.id
                            AND pp.company_id = pos.company_id
                        JOIN sys_permission p ON p.id = pp.permission_id
                            AND p.status = 'ENABLED' AND p.deleted_at IS NULL
                        WHERE up.user_id = ? AND up.company_id = ?
                          AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                        ORDER BY p.permission_code
                        """, String.class, account.id(), membership.companyId());
        return new ManagementUser(
                account.id(), membership.companyId(), membership.companyName(),
                account.username(), account.displayName(), roles, permissions,
                account.mustChangePassword(), account.authVersion());
    }

    public void recordLoginSuccess(long id) {
        jdbcTemplate.update(
                "UPDATE sys_user SET login_failed_count = 0, locked_until = NULL, last_login_at = NOW() WHERE id = ?",
                id);
    }

    public void recordLoginFailure(long id, int currentFailures) {
        int failures = currentFailures + 1;
        if (failures >= 5) {
            jdbcTemplate.update(
                    "UPDATE sys_user SET login_failed_count = ?, locked_until = DATE_ADD(NOW(), INTERVAL 15 MINUTE) WHERE id = ?",
                    failures, id);
        } else {
            jdbcTemplate.update("UPDATE sys_user SET login_failed_count = ? WHERE id = ?", failures, id);
        }
    }

    public void updatePassword(long id, String passwordHash) {
        jdbcTemplate.update(
                """
                UPDATE sys_user
                SET password_hash = ?, must_change_password = 0, password_changed_at = NOW(),
                    auth_version = auth_version + 1, version = version + 1
                WHERE id = ? AND deleted_at IS NULL
                """, passwordHash, id);
    }

    private UserAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("user_type"), rs.getString("status"),
                rs.getBoolean("must_change_password"), rs.getInt("login_failed_count"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime(),
                rs.getLong("auth_version"));
    }

    private record CompanyMembership(long companyId, String companyName, boolean companyAdmin) {
    }
}
