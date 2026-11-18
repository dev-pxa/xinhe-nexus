CREATE TABLE IF NOT EXISTS sys_user_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户额外权限关系主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID，用于公司数据隔离',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    permission_id BIGINT UNSIGNED NOT NULL COMMENT '额外授予的权限ID',
    created_by BIGINT UNSIGNED NULL COMMENT '配置权限的用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_permission (company_id, user_id, permission_id),
    KEY idx_user_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户额外功能权限关系表';

-- 生成列只标记当前有效关系；MySQL 允许多个 NULL，因此历史停用记录可以保留。
ALTER TABLE sys_user_position
    DROP INDEX uk_user_position,
    ADD COLUMN active_user_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN status = 'ENABLED' AND deleted_at IS NULL THEN user_id ELSE NULL END) STORED
        COMMENT '当前有效岗位关系的用户ID，用于单岗位唯一约束',
    ADD UNIQUE KEY uk_company_active_user_position (company_id, active_user_id),
    ADD UNIQUE KEY uk_user_position (user_id, position_id);
