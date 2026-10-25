package com.xinhe.nexus.management.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SchemaContractTest {
    @Test
    void schemaMatchesApprovedManagementModel() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V1__management_system.sql")) {
            assertTrue(stream != null, "数据库迁移文件必须存在");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("channel_level TINYINT"));
        assertFalse(sql.contains("short_name"));
        assertFalse(sql.contains("relation_level"));
        assertFalse(sql.contains("headcount"));
        assertFalse(sql.contains("effective_from"));
        assertFalse(sql.contains("effective_to"));
        assertFalse(sql.contains("'API'"));
        assertTrue(sql.contains("permission_kind VARCHAR(16) NOT NULL COMMENT '权限类型：MODULE模块、MENU菜单、ACTION操作'"));
    }

    @Test
    void everyBusinessColumnHasAComment() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V1__management_system.sql")) {
            assertTrue(stream != null);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        boolean insideTable = false;
        for (String originalLine : sql.lines().toList()) {
            String line = originalLine.trim();
            if (line.startsWith("CREATE TABLE IF NOT EXISTS sys_")) {
                insideTable = true;
                continue;
            }
            if (insideTable && line.startsWith(") ENGINE=")) {
                insideTable = false;
                continue;
            }
            if (!insideTable || line.isBlank() || line.startsWith("PRIMARY KEY")
                    || line.startsWith("UNIQUE KEY") || line.startsWith("KEY ")) {
                continue;
            }
            assertTrue(line.contains(" COMMENT '"), "字段缺少注释：" + line);
        }
    }

    @Test
    void primaryPositionIsRemovedByMigration() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V2__drop_primary_position.sql")) {
            assertTrue(stream != null, "删除主岗位字段的数据库迁移文件必须存在");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("ALTER TABLE sys_user_position"));
        assertTrue(sql.contains("DROP COLUMN is_primary"));
    }

    @Test
    void organizationManagerPositionIsAddedByMigration() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V3__add_org_manager_position.sql")) {
            assertTrue(stream != null, "组织负责人岗位迁移文件必须存在");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("ALTER TABLE sys_org_unit"));
        assertTrue(sql.contains("ADD COLUMN manager_position_id"));
    }

    @Test
    void attendanceTablesAreAddedByMigration() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V4__add_oa_attendance_punch.sql")) {
            assertTrue(stream != null, "OA打卡迁移文件必须存在");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_attendance_punch"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_attendance_day_summary"));
        assertTrue(sql.contains("first_punch_time DATETIME"));
        assertTrue(sql.contains("last_punch_time DATETIME"));
    }

    @Test
    void expenseTablesAreAddedByMigration() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V5__add_oa_expense_claim.sql")) {
            assertTrue(stream != null, "OA报销迁移文件必须存在");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_expense_claim"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_expense_item"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_expense_attachment"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS oa_expense_approval_node"));
        assertTrue(sql.contains("REJECTED已驳回"));
    }
}
