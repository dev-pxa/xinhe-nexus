SET @legacy_user_table_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user'
      AND column_name = 'tenant_id'
);
SET @legacy_user_backup_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_user_legacy'
);
SET @rename_legacy_user_sql = IF(
    @legacy_user_table_exists > 0 AND @legacy_user_backup_exists = 0,
    'RENAME TABLE sys_user TO sys_user_legacy',
    'SELECT 1'
);
PREPARE rename_legacy_user_statement FROM @rename_legacy_user_sql;
EXECUTE rename_legacy_user_statement;
DEALLOCATE PREPARE rename_legacy_user_statement;

CREATE TABLE IF NOT EXISTS sys_company (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '公司主键ID',
    company_code VARCHAR(32) NOT NULL COMMENT '公司编号，全局唯一，创建后不可修改',
    company_name VARCHAR(128) NOT NULL COMMENT '公司完整名称',
    channel_level TINYINT NOT NULL DEFAULT 1 COMMENT '渠道层级，取值1至4',
    contact_name VARCHAR(64) NULL COMMENT '公司联系人姓名',
    contact_phone VARCHAR(32) NULL COMMENT '公司联系人电话',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '公司状态：ENABLED启用、DISABLED停用',
    auth_version BIGINT NOT NULL DEFAULT 1 COMMENT '公司权限版本，用于使公司权限缓存失效',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止并发修改覆盖',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_code (company_code),
    KEY idx_company_status (status),
    KEY idx_company_channel_level (channel_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司信息表';

CREATE TABLE IF NOT EXISTS sys_company_relation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '公司渠道关系主键ID',
    upstream_company_id BIGINT UNSIGNED NOT NULL COMMENT '上级渠道公司ID',
    downstream_company_id BIGINT UNSIGNED NOT NULL COMMENT '下级渠道公司ID',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '关系状态：ENABLED有效、DISABLED失效',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_downstream_company (downstream_company_id),
    KEY idx_relation_upstream (upstream_company_id),
    KEY idx_relation_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司渠道协作关系表';

CREATE TABLE IF NOT EXISTS sys_org_unit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '组织主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '上级组织ID，根组织为空',
    org_code VARCHAR(32) NOT NULL COMMENT '组织编码，由系统自动生成，创建后不可修改',
    org_name VARCHAR(128) NOT NULL COMMENT '组织名称',
    org_path VARCHAR(1024) NOT NULL COMMENT '组织层级路径，例如/1/10/20/',
    org_level INT NOT NULL COMMENT '组织在公司内部的树形层级，根组织为1',
    manager_user_id BIGINT UNSIGNED NULL COMMENT '组织负责人用户ID',
    contact_phone VARCHAR(32) NULL COMMENT '组织联系电话',
    description VARCHAR(500) NULL COMMENT '组织说明',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '组织状态：ENABLED启用、DISABLED停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止并发修改覆盖',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_org_code (company_id, org_code),
    KEY idx_org_parent (company_id, parent_id),
    KEY idx_org_manager (company_id, manager_user_id),
    KEY idx_org_status (company_id, status),
    KEY idx_org_path (company_id, org_path(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司内部组织架构表';

CREATE TABLE IF NOT EXISTS sys_position (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '岗位主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    org_unit_id BIGINT UNSIGNED NOT NULL COMMENT '所属组织ID',
    position_code VARCHAR(32) NOT NULL COMMENT '岗位编码，由系统自动生成',
    position_name VARCHAR(128) NOT NULL COMMENT '岗位名称',
    responsibilities VARCHAR(1000) NULL COMMENT '岗位职责说明',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '岗位状态：ENABLED启用、DISABLED停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止并发修改覆盖',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_position_code (company_id, position_code),
    KEY idx_position_org (company_id, org_unit_id),
    KEY idx_position_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司岗位表';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号，全局唯一',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt加密后的密码哈希，不保存明文密码',
    display_name VARCHAR(64) NOT NULL COMMENT '用户显示名称',
    user_type VARCHAR(32) NOT NULL DEFAULT 'COMPANY_USER' COMMENT '用户类型：PLATFORM_ADMIN平台管理员、COMPANY_USER公司用户',
    phone VARCHAR(32) NULL COMMENT '用户手机号码',
    email VARCHAR(128) NULL COMMENT '用户电子邮箱',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED启用、DISABLED停用、LOCKED锁定',
    must_change_password TINYINT NOT NULL DEFAULT 0 COMMENT '是否提示修改初始密码：0否、1是',
    login_failed_count INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until DATETIME NULL COMMENT '账号临时锁定截止时间',
    last_login_at DATETIME NULL COMMENT '最后一次登录成功时间',
    password_changed_at DATETIME NULL COMMENT '最后一次修改密码时间',
    auth_version BIGINT NOT NULL DEFAULT 1 COMMENT '用户权限及会话版本，用于使个人缓存和Session失效',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，防止并发修改覆盖',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_status (status),
    KEY idx_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统登录用户账号表';

CREATE TABLE IF NOT EXISTS sys_user_company (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户公司关系主键ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    is_company_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否为公司管理员：0否、1是',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '成员状态：ENABLED有效、DISABLED停用',
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入公司时间',
    left_at DATETIME NULL COMMENT '离开公司时间',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_company (user_id, company_id),
    KEY idx_user_company_company (company_id, status),
    KEY idx_user_company_admin (company_id, is_company_admin, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户与公司成员关系表';

CREATE TABLE IF NOT EXISTS sys_user_position (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户岗位关系主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID，用于公司数据隔离',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    position_id BIGINT UNSIGNED NOT NULL COMMENT '岗位ID',
    is_primary TINYINT NOT NULL DEFAULT 0 COMMENT '是否为用户主岗位：0否、1是',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '关系状态：ENABLED有效、DISABLED停用',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_position (user_id, position_id),
    KEY idx_user_position_user (company_id, user_id, status),
    KEY idx_user_position_position (company_id, position_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户岗位关系表';

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限主键ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '上级权限节点ID，顶级模块为空',
    permission_code VARCHAR(128) NOT NULL COMMENT '权限编码，例如system:user:create',
    permission_name VARCHAR(128) NOT NULL COMMENT '权限显示名称',
    permission_kind VARCHAR(16) NOT NULL COMMENT '权限类型：MODULE模块、MENU菜单、ACTION操作',
    module_code VARCHAR(32) NOT NULL COMMENT '所属业务模块编码，例如SYSTEM、CRM、ERP、OA',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '权限状态：ENABLED启用、DISABLED停用',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示未删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code),
    KEY idx_permission_parent (parent_id),
    KEY idx_permission_module (module_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统功能权限定义表';

CREATE TABLE IF NOT EXISTS sys_position_permission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '岗位权限关系主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID，用于公司数据隔离',
    position_id BIGINT UNSIGNED NOT NULL COMMENT '岗位ID',
    permission_id BIGINT UNSIGNED NOT NULL COMMENT '权限ID',
    created_by BIGINT UNSIGNED NULL COMMENT '配置权限的用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_position_permission (position_id, permission_id),
    KEY idx_position_permission_company (company_id, position_id),
    KEY idx_position_permission_permission (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='岗位功能权限关系表';

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '操作日志主键ID',
    company_id BIGINT UNSIGNED NULL COMMENT '操作所属公司ID，平台操作可以为空',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '操作用户ID',
    username VARCHAR(64) NOT NULL COMMENT '操作时的登录账号快照',
    module_code VARCHAR(32) NOT NULL COMMENT '操作所属模块编码',
    operation_type VARCHAR(32) NOT NULL COMMENT '操作类型，例如CREATE、UPDATE、DELETE、RESET_PASSWORD',
    target_type VARCHAR(64) NULL COMMENT '被操作对象类型',
    target_id VARCHAR(64) NULL COMMENT '被操作对象ID',
    request_id VARCHAR(64) NULL COMMENT '请求链路编号',
    request_ip VARCHAR(64) NULL COMMENT '请求来源IP地址',
    before_data JSON NULL COMMENT '修改前数据，不记录密码等敏感字段',
    after_data JSON NULL COMMENT '修改后数据，不记录密码等敏感字段',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作发生时间',
    PRIMARY KEY (id),
    KEY idx_operation_company (company_id, created_at),
    KEY idx_operation_user (user_id, created_at),
    KEY idx_operation_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统操作审计日志表';

INSERT IGNORE INTO sys_permission
    (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('system', '系统管理', 'MODULE', 'SYSTEM'),
    ('system:company', '公司管理', 'MENU', 'SYSTEM'),
    ('system:company:view', '查看公司', 'ACTION', 'SYSTEM'),
    ('system:company:create', '创建公司', 'ACTION', 'SYSTEM'),
    ('system:company:update', '编辑公司', 'ACTION', 'SYSTEM'),
    ('system:org', '组织与岗位', 'MENU', 'SYSTEM'),
    ('system:org:view', '查看组织', 'ACTION', 'SYSTEM'),
    ('system:org:create', '创建组织', 'ACTION', 'SYSTEM'),
    ('system:org:update', '编辑组织', 'ACTION', 'SYSTEM'),
    ('system:org:delete', '删除组织', 'ACTION', 'SYSTEM'),
    ('system:position:view', '查看岗位', 'ACTION', 'SYSTEM'),
    ('system:position:create', '创建岗位', 'ACTION', 'SYSTEM'),
    ('system:position:update', '编辑岗位', 'ACTION', 'SYSTEM'),
    ('system:position:delete', '删除岗位', 'ACTION', 'SYSTEM'),
    ('system:position:permission', '配置岗位权限', 'ACTION', 'SYSTEM'),
    ('system:user', '用户管理', 'MENU', 'SYSTEM'),
    ('system:user:view', '查看用户', 'ACTION', 'SYSTEM'),
    ('system:user:create', '创建用户', 'ACTION', 'SYSTEM'),
    ('system:user:update', '编辑用户', 'ACTION', 'SYSTEM'),
    ('system:user:disable', '停用用户', 'ACTION', 'SYSTEM'),
    ('system:user:reset-password', '重置密码', 'ACTION', 'SYSTEM'),
    ('system:user:assign-position', '分配岗位', 'ACTION', 'SYSTEM');
