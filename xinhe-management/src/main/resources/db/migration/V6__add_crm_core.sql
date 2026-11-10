-- CRM 首期：每日计划、客户档案与客户跟踪。合同及转 ERP 订单暂不建表。
CREATE TABLE IF NOT EXISTS crm_customer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属下级公司ID，来自当前登录用户公司',
    customer_no VARCHAR(32) NOT NULL COMMENT '公司内唯一客户编号',
    customer_name VARCHAR(160) NOT NULL COMMENT '客户名称',
    phone VARCHAR(40) NULL COMMENT '联系电话',
    address VARCHAR(500) NULL COMMENT '客户详细地址',
    source VARCHAR(255) NULL COMMENT '客户来源渠道',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '客户负责人用户ID',
    remark VARCHAR(1000) NULL COMMENT '客户备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次更新递增',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后更新人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_no (company_id, customer_no),
    KEY idx_crm_customer_owner (company_id, owner_user_id, deleted_at),
    KEY idx_crm_customer_name (company_id, customer_name),
    KEY idx_crm_customer_phone (company_id, phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户档案';

CREATE TABLE IF NOT EXISTS crm_customer_collaborator (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户协作人关系主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    customer_id BIGINT UNSIGNED NOT NULL COMMENT '关联客户ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '协作人用户ID',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '添加协作人的操作者用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
    deleted_at DATETIME NULL COMMENT '移除时间，为空表示当前协作关系有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_collaborator (company_id, customer_id, user_id),
    KEY idx_crm_collaborator_user (company_id, user_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户协作人关系';

CREATE TABLE IF NOT EXISTS crm_customer_transfer_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户负责人变更日志主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    customer_id BIGINT UNSIGNED NOT NULL COMMENT '被变更负责人的客户ID',
    from_user_id BIGINT UNSIGNED NOT NULL COMMENT '变更前负责人用户ID',
    to_user_id BIGINT UNSIGNED NOT NULL COMMENT '变更后负责人用户ID',
    reason VARCHAR(500) NOT NULL COMMENT '负责人变更原因',
    operated_by BIGINT UNSIGNED NOT NULL COMMENT '执行变更的操作者用户ID',
    operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '负责人变更时间',
    PRIMARY KEY (id),
    KEY idx_crm_customer_transfer (company_id, customer_id, operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户负责人变更历史';

CREATE TABLE IF NOT EXISTS crm_daily_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '每日计划表头主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '每日计划归属用户ID，创建后不可修改',
    owner_org_id BIGINT UNSIGNED NULL COMMENT '创建时归属组织ID快照',
    plan_date DATE NOT NULL COMMENT '每日计划日期，创建后不可修改',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '每日计划创建人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '每日计划创建时间',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '每日计划最后更新人用户ID',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '每日计划最后更新时间',
    deleted_by BIGINT UNSIGNED NULL COMMENT '软删除操作者用户ID',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_daily_plan_owner_day (company_id, owner_user_id, plan_date),
    KEY idx_crm_daily_plan_org_day (company_id, owner_org_id, plan_date, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM每日计划表头';

CREATE TABLE IF NOT EXISTS crm_daily_plan_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '每日计划项主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    daily_plan_id BIGINT UNSIGNED NOT NULL COMMENT '所属每日计划表头ID',
    content_html MEDIUMTEXT NOT NULL COMMENT '计划具体内容的安全富文本HTML',
    content_text TEXT NOT NULL COMMENT '计划具体内容纯文本，用于检索和摘要',
    customer_id BIGINT UNSIGNED NULL COMMENT '关联客户ID，可为空',
    is_completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完成，0未完成、1已完成',
    completed_at DATETIME NULL COMMENT '完成时间，未完成时为空',
    completed_by BIGINT UNSIGNED NULL COMMENT '完成操作用户ID，未完成时为空',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '计划项在每日计划中的显示顺序',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '计划项创建人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '计划项创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '计划项最后更新时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    KEY idx_crm_plan_item_parent (company_id, daily_plan_id, deleted_at, sort_order),
    KEY idx_crm_plan_item_customer (company_id, customer_id),
    KEY idx_crm_plan_item_completion (company_id, is_completed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM每日计划内容项';

CREATE TABLE IF NOT EXISTS crm_follow_up (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户跟踪记录主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    customer_id BIGINT UNSIGNED NOT NULL COMMENT '跟踪记录所属客户ID',
    occurred_at DATETIME NOT NULL COMMENT '实际跟进发生时间',
    method VARCHAR(24) NOT NULL COMMENT '跟进方式：VISIT拜访、PHONE电话、WECHAT微信、ONLINE线上、OTHER其他',
    content TEXT NOT NULL COMMENT '客户沟通内容',
    customer_feedback TEXT NULL COMMENT '客户反馈内容',
    quote_summary VARCHAR(1000) NULL COMMENT '报价或预算情况说明',
    quote_amount DECIMAL(18,2) NULL COMMENT '报价或预算金额',
    commitment TEXT NULL COMMENT '双方承诺事项',
    next_action VARCHAR(1000) NULL COMMENT '下一步跟进动作',
    next_action_at DATETIME NULL COMMENT '下一步跟进时间',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '跟踪记录录入人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '跟踪记录创建时间',
    correction_of_id BIGINT UNSIGNED NULL COMMENT '被本记录补充更正的跟踪记录ID',
    PRIMARY KEY (id),
    KEY idx_crm_followup_customer_time (company_id, customer_id, occurred_at),
    KEY idx_crm_followup_creator_time (company_id, created_by, occurred_at),
    KEY idx_crm_followup_next_action (company_id, next_action_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户跟踪时间轴';

CREATE TABLE IF NOT EXISTS crm_follow_up_attachment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '跟踪附件关联主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    follow_up_id BIGINT UNSIGNED NOT NULL COMMENT '关联客户跟踪记录ID',
    file_name VARCHAR(255) NOT NULL COMMENT '上传时的原始文件名',
    object_key VARCHAR(512) NOT NULL COMMENT '文件存储对象键，不直接暴露给浏览器',
    content_type VARCHAR(128) NOT NULL COMMENT '文件媒体类型',
    file_size BIGINT UNSIGNED NOT NULL COMMENT '文件大小，单位字节',
    uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '上传人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '附件上传时间',
    deleted_at DATETIME NULL COMMENT '附件软删除时间，为空表示有效',
    PRIMARY KEY (id),
    KEY idx_crm_followup_attachment (company_id, follow_up_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户跟踪附件';

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT NULL, 'crm', 'CRM客户销售', 'MODULE', 'CRM'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'crm');

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'crm'), 'crm:customers', '客户管理', 'MENU', 'CRM'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'crm:customers');

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'crm'), 'crm:daily-plans', '每日计划', 'MENU', 'CRM'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'crm:daily-plans');

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'crm:customers'), codes.permission_code, codes.permission_name, 'ACTION', 'CRM'
FROM (
    SELECT 'crm:customer:view' permission_code, '查看客户' permission_name UNION ALL
    SELECT 'crm:customer:create', '创建客户' UNION ALL
    SELECT 'crm:customer:edit', '编辑客户' UNION ALL
    SELECT 'crm:customer:team-view', '查看负责组织树内客户' UNION ALL
    SELECT 'crm:customer:owner:edit', '修改客户负责人' UNION ALL
    SELECT 'crm:customer:collaborator:edit', '维护客户协作人' UNION ALL
    SELECT 'crm:customer:transfer', '转交客户' UNION ALL
    SELECT 'crm:followup:view', '查看客户跟踪' UNION ALL
    SELECT 'crm:followup:create', '新增客户跟踪' UNION ALL
    SELECT 'crm:attachment:upload', '上传客户跟踪附件' UNION ALL
    SELECT 'crm:attachment:download', '下载客户跟踪附件'
) codes;

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'crm:daily-plans'), codes.permission_code, codes.permission_name, 'ACTION', 'CRM'
FROM (
    SELECT 'crm:plan:view' permission_code, '查看每日计划' permission_name UNION ALL
    SELECT 'crm:plan:create', '创建每日计划' UNION ALL
    SELECT 'crm:plan:edit', '编辑每日计划' UNION ALL
    SELECT 'crm:plan:delete', '删除每日计划' UNION ALL
    SELECT 'crm:plan:complete', '完成每日计划项' UNION ALL
    SELECT 'crm:plan:team-view', '查看负责组织树内每日计划'
) codes;
