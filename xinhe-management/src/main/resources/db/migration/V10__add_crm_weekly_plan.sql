-- CRM 每周计划：独立于每日计划保存，按公司、归属人和周起始日隔离。
CREATE TABLE crm_weekly_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '每周计划主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '每周计划归属用户ID，创建后不可修改',
    owner_org_id BIGINT UNSIGNED NULL COMMENT '创建时归属组织ID快照，用于负责人查看下属计划',
    week_start DATE NOT NULL COMMENT '计划周期起始日期，固定为周一',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '每周计划创建人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '每周计划创建时间',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '每周计划最后更新人用户ID',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '每周计划最后更新时间',
    deleted_by BIGINT UNSIGNED NULL COMMENT '软删除操作者用户ID',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_weekly_plan_owner_week (company_id, owner_user_id, week_start),
    KEY idx_crm_weekly_plan_org_week (company_id, owner_org_id, week_start, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM每周计划表头';

CREATE TABLE crm_weekly_plan_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '每周计划项主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    weekly_plan_id BIGINT UNSIGNED NOT NULL COMMENT '所属每周计划表头ID',
    content_html MEDIUMTEXT NOT NULL COMMENT '计划具体内容的安全富文本HTML',
    content_text TEXT NOT NULL COMMENT '计划具体内容纯文本，用于检索和摘要',
    customer_id BIGINT UNSIGNED NULL COMMENT '关联客户ID，可为空',
    remark VARCHAR(1000) NULL COMMENT '每周计划项备注信息',
    is_completed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完成，0未完成、1已完成',
    completed_at DATETIME NULL COMMENT '完成时间，未完成时为空',
    completed_by BIGINT UNSIGNED NULL COMMENT '完成操作用户ID，未完成时为空',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '计划项在每周计划中的显示顺序',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '计划项创建人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '计划项创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '计划项最后更新时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    KEY idx_crm_weekly_plan_item_parent (company_id, weekly_plan_id, deleted_at, sort_order),
    KEY idx_crm_weekly_plan_item_customer (company_id, customer_id),
    KEY idx_crm_weekly_plan_item_completion (company_id, is_completed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM每周计划内容项';
