-- CRM 分类、个人收藏和每日计划项备注；每张表均按公司及当前用户隔离。
CREATE TABLE crm_customer_category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户分类主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '父级分类ID，空表示一级分类',
    category_name VARCHAR(100) NOT NULL COMMENT '分类名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '分类排序值',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间，为空表示有效',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_category_company_name_parent (company_id, parent_id, category_name),
    KEY idx_crm_category_parent (company_id, parent_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM客户自定义多级分类';

ALTER TABLE crm_customer
    ADD COLUMN category_id BIGINT UNSIGNED NULL COMMENT '客户所属分类ID' AFTER source,
    ADD KEY idx_crm_customer_category (company_id, category_id, deleted_at);

CREATE TABLE crm_customer_favorite (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户收藏关系主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    customer_id BIGINT UNSIGNED NOT NULL COMMENT '被收藏客户ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '收藏该客户的用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次收藏时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后收藏状态变更时间',
    deleted_at DATETIME NULL COMMENT '取消收藏时间，为空表示已收藏',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_favorite (company_id, customer_id, user_id),
    KEY idx_crm_favorite_user (company_id, user_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM用户个人客户收藏';

ALTER TABLE crm_daily_plan_item
    ADD COLUMN remark VARCHAR(1000) NULL COMMENT '每日计划项备注信息' AFTER customer_id;

UPDATE crm_customer
SET source='其他'
WHERE source IS NOT NULL
  AND source NOT IN ('抖音','小红书','老客户推荐','设计师推荐','材料商推荐','扫楼','其他');
