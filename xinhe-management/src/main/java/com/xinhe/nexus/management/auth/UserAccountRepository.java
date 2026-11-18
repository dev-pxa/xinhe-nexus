package com.xinhe.nexus.management.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;

@Repository
public class UserAccountRepository {
    private final JdbcTemplate jdbcTemplate;
    private final String headquartersCode;

    public UserAccountRepository(JdbcTemplate jdbcTemplate,
                                 @Value("${xinhe.bootstrap.headquarters-code:XINHE}") String headquartersCode) {
        this.jdbcTemplate = jdbcTemplate;
        this.headquartersCode = headquartersCode;
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
                SELECT uc.company_id, c.company_name, c.company_code, uc.is_company_admin
                FROM sys_user_company uc
                JOIN sys_company c ON c.id = uc.company_id AND c.deleted_at IS NULL
                WHERE uc.user_id = ? AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                  AND c.status = 'ENABLED'
                ORDER BY uc.is_company_admin DESC, uc.id LIMIT 1
                """,
                (rs, rowNum) -> new CompanyMembership(
                        rs.getLong("company_id"), rs.getString("company_name"),
                        rs.getBoolean("is_company_admin"), rs.getString("company_code")), account.id());
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
                        SELECT permission_code FROM (
                            SELECT p.permission_code
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
                            UNION
                            SELECT p.permission_code
                            FROM sys_user_permission uperm
                            JOIN sys_permission p ON p.id = uperm.permission_id
                                AND p.status = 'ENABLED' AND p.deleted_at IS NULL
                            WHERE uperm.user_id = ? AND uperm.company_id = ?
                        ) effective_permissions
                        ORDER BY permission_code
                        """, String.class, account.id(), membership.companyId(), account.id(), membership.companyId());
        return new ManagementUser(
                account.id(), membership.companyId(), membership.companyName(),
                account.username(), account.displayName(), roles, permissions,
                account.mustChangePassword(), account.authVersion(),
                headquartersCode.equalsIgnoreCase(membership.companyCode()));
    }

    public void recordLoginSuccess(long id) {
        jdbcTemplate.update(
                "UPDATE sys_user SET login_failed_count = 0, locked_until = NULL, last_login_at = NOW() WHERE id = ?",
                id);
    }

    public void recordLoginFailure(long id) {
        jdbcTemplate.update("UPDATE sys_user SET login_failed_count = login_failed_count + 1 WHERE id = ?", id);
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

    public PersonalProfile findProfile(long id) {
        List<PersonalProfile> profiles = jdbcTemplate.query(
                """
                SELECT username, display_name, phone, email
                FROM sys_user WHERE id = ? AND deleted_at IS NULL
                """,
                (rs, rowNum) -> new PersonalProfile(
                        rs.getString("username"), rs.getString("display_name"),
                        rs.getString("phone"), rs.getString("email")), id);
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public void updateProfile(long id, String displayName, String phone, String email) {
        jdbcTemplate.update(
                """
                UPDATE sys_user
                SET display_name = ?, phone = ?, email = ?, version = version + 1,
                    updated_by = ?, updated_at = NOW()
                WHERE id = ? AND deleted_at IS NULL
                """, displayName, phone, email, id, id);
        // 账号基础资料与人员档案中的同名字段保持同一事务内同步。
        jdbcTemplate.update(
                """
                UPDATE hr_employee_profile
                SET candidate_name = ?, phone = ?, email = ?, updated_at = NOW()
                WHERE user_id = ?
                """, displayName, phone, email, id);
    }

    private UserAccount mapAccount(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("user_type"), rs.getString("status"),
                rs.getBoolean("must_change_password"), rs.getInt("login_failed_count"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime(),
                rs.getLong("auth_version"));
    }

    private record CompanyMembership(long companyId, String companyName, boolean companyAdmin, String companyCode) {
    }
}
