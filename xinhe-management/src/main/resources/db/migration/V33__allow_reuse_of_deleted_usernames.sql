-- 保留初始化账号的用户名，防止普通用户复用后被初始化流程授予管理员权限。
ALTER TABLE sys_user
    DROP INDEX uk_user_username,
    ADD COLUMN username_reserved TINYINT
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL OR username IN ('root', 'admin') THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_user_username (username, username_reserved);
