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
}
