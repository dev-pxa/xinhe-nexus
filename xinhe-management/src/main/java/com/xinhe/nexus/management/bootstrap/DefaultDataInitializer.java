package com.xinhe.nexus.management.bootstrap;

import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String headquartersCode;
    private final String headquartersName;

    public DefaultDataInitializer(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${xinhe.bootstrap.headquarters-code:XINHE}") String headquartersCode,
            @Value("${xinhe.bootstrap.headquarters-name:芯河智枢总公司}") String headquartersName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.headquartersCode = headquartersCode.trim().toUpperCase();
        this.headquartersName = headquartersName.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createPlatformRoot();
        long headquartersId = createHeadquarters();
        createRootOrganization(headquartersId, headquartersCode, headquartersName);
        createHeadquartersAdmin(headquartersId);
    }

    private void createPlatformRoot() {
        if (count("SELECT COUNT(*) FROM sys_user WHERE username = 'root' AND deleted_at IS NULL") > 0) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO sys_user
                    (username, password_hash, display_name, user_type, status, must_change_password)
                VALUES ('root', ?, '平台管理员', 'PLATFORM_ADMIN', 'ENABLED', 1)
                """,
                passwordEncoder.encode("root")
        );
    }

    private long createHeadquarters() {
        Long existing = queryId("SELECT id FROM sys_company WHERE company_code = ? AND deleted_at IS NULL", headquartersCode);
        if (existing != null) {
            return existing;
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sys_company (company_code, company_name, channel_level, status) VALUES (?, ?, 1, 'ENABLED')",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, headquartersCode);
            statement.setString(2, headquartersName);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void createRootOrganization(long companyId, String companyCode, String companyName) {
        if (count("SELECT COUNT(*) FROM sys_org_unit WHERE company_id = ? AND parent_id IS NULL AND deleted_at IS NULL", companyId) > 0) {
            return;
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO sys_org_unit
                        (company_id, parent_id, org_code, org_name, org_path, org_level, status)
                    VALUES (?, NULL, ?, ?, '/', 1, 'ENABLED')
                    """,
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, companyId);
            statement.setString(2, "ORG-" + companyCode + "-ROOT");
            statement.setString(3, companyName);
            return statement;
        }, keyHolder);
        long orgId = keyHolder.getKey().longValue();
        jdbcTemplate.update("UPDATE sys_org_unit SET org_path = ? WHERE id = ?", "/" + orgId + "/", orgId);
    }

    private void createHeadquartersAdmin(long companyId) {
        Long userId = queryId("SELECT id FROM sys_user WHERE username = 'admin' AND deleted_at IS NULL");
        if (userId == null) {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO sys_user
                            (username, password_hash, display_name, user_type, status, must_change_password)
                        VALUES ('admin', ?, '总公司管理员', 'COMPANY_USER', 'ENABLED', 1)
                        """,
                        Statement.RETURN_GENERATED_KEYS
                );
                statement.setString(1, passwordEncoder.encode("admin"));
                return statement;
            }, keyHolder);
            userId = keyHolder.getKey().longValue();
        }
        if (count("SELECT COUNT(*) FROM sys_user_company WHERE user_id = ? AND company_id = ?", userId, companyId) == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO sys_user_company (user_id, company_id, is_company_admin, status)
                    VALUES (?, ?, 1, 'ENABLED')
                    """, userId, companyId);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private Long queryId(String sql, Object... args) {
        var values = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), args);
        return values.isEmpty() ? null : values.get(0);
    }
}
