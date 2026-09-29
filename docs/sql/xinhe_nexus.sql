CREATE DATABASE IF NOT EXISTS xinhe_nexus
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

-- 业务表由 xinhe-management 启动时通过 Flyway 版本化创建和升级。
-- 完整建表语句位于：
-- xinhe-management/src/main/resources/db/migration/V1__management_system.sql
-- 迁移文件中的每张表和每个字段均包含中文 COMMENT。
