CREATE TABLE IF NOT EXISTS oa_expense_claim (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '报销单主键ID',
    claim_no VARCHAR(32) NOT NULL COMMENT '报销单号，由系统自动生成',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    applicant_user_id BIGINT UNSIGNED NOT NULL COMMENT '申请人用户ID',
    applicant_org_id BIGINT UNSIGNED NULL COMMENT '申请时所属组织ID',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '四类费用合计金额',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT草稿、PENDING待审核、REVIEWING审核中、APPROVED审核通过、REJECTED已驳回',
    current_reviewer_id BIGINT UNSIGNED NULL COMMENT '当前审核人用户ID',
    first_reviewed_at DATETIME NULL COMMENT '第一次产生审核动作的时间',
    submitted_at DATETIME NULL COMMENT '提交时间',
    approved_at DATETIME NULL COMMENT '审核通过时间',
    rejected_at DATETIME NULL COMMENT '驳回时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    deleted_at DATETIME NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_expense_claim_no (company_id, claim_no),
    KEY idx_expense_claim_applicant (company_id, applicant_user_id, status),
    KEY idx_expense_claim_status (company_id, status, current_reviewer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA报销单主表';

CREATE TABLE IF NOT EXISTS oa_expense_item (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '费用明细主键ID',
    claim_id BIGINT UNSIGNED NOT NULL COMMENT '报销单ID',
    expense_category VARCHAR(24) NOT NULL COMMENT '费用类型：ACCOMMODATION住宿、MEAL餐饮、TRANSPORT交通、OTHER其他',
    expense_date DATE NULL COMMENT '费用发生日期',
    expense_location VARCHAR(255) NULL COMMENT '费用发生地点',
    amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '费用金额',
    remark VARCHAR(500) NULL COMMENT '费用说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    KEY idx_expense_item_claim (claim_id, expense_category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA报销费用明细表';

CREATE TABLE IF NOT EXISTS oa_expense_attachment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '附件主键ID',
    claim_id BIGINT UNSIGNED NOT NULL COMMENT '报销单ID',
    item_id BIGINT UNSIGNED NOT NULL COMMENT '费用明细ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    attachment_kind VARCHAR(16) NOT NULL COMMENT '附件类型：INVOICE发票、OTHER其他附件',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    object_key VARCHAR(512) NOT NULL COMMENT '文件存储路径',
    content_type VARCHAR(128) NOT NULL COMMENT '文件MIME类型',
    file_size BIGINT NOT NULL COMMENT '文件大小，单位字节',
    uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '上传用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    deleted_at DATETIME NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    KEY idx_expense_attachment_claim (claim_id, item_id),
    KEY idx_expense_attachment_company (company_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA报销附件表';

CREATE TABLE IF NOT EXISTS oa_expense_approval_node (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审核流程节点主键ID',
    claim_id BIGINT UNSIGNED NOT NULL COMMENT '报销单ID',
    sequence_no INT NOT NULL COMMENT '审核顺序，从1开始',
    org_unit_id BIGINT UNSIGNED NULL COMMENT '节点对应组织ID',
    reviewer_user_id BIGINT UNSIGNED NULL COMMENT '审核人用户ID',
    reviewer_position_id BIGINT UNSIGNED NULL COMMENT '审核人岗位ID',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '节点状态：PENDING待审核、APPROVED通过、REJECTED驳回',
    opinion VARCHAR(1000) NULL COMMENT '审核意见',
    acted_at DATETIME NULL COMMENT '审核时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_expense_approval_sequence (claim_id, sequence_no),
    KEY idx_expense_approval_reviewer (reviewer_user_id, status),
    KEY idx_expense_approval_claim (claim_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA报销逐级审核节点表';

INSERT IGNORE INTO sys_permission (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('oa:expense', '我的报销', 'MENU', 'OA'),
    ('oa:expense:view', '查看我的报销', 'ACTION', 'OA'),
    ('oa:expense:create', '新建报销', 'ACTION', 'OA'),
    ('oa:expense:submit', '提交报销', 'ACTION', 'OA'),
    ('oa:expense:approve', '审核报销', 'ACTION', 'OA');
