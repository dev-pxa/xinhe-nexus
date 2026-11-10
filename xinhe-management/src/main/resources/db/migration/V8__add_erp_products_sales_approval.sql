-- ERP 首期当前实现的商品资料与销售订单部门负责人审批基础表。
CREATE TABLE erp_product (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属总公司账套ID',
    product_code VARCHAR(40) NOT NULL COMMENT '系统自动生成的商品编码',
    product_name VARCHAR(160) NOT NULL COMMENT '商品名称',
    unit_price DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '商品默认销售单价',
    remark VARCHAR(1000) NULL COMMENT '商品备注',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '商品状态：ENABLED启用、DISABLED停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_code (company_id, product_code),
    KEY idx_erp_product_name (company_id, product_name),
    KEY idx_erp_product_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP商品资料表（无规格、单位和供应商标识）';

CREATE TABLE erp_sales_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售订单主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属总公司账套ID',
    application_no VARCHAR(40) NOT NULL COMMENT '订单申请编号，提交时生成',
    order_no VARCHAR(40) NULL COMMENT '正式销售订单编号，审批通过后生成',
    customer_name VARCHAR(160) NOT NULL COMMENT '客户名称快照',
    contact_name VARCHAR(80) NULL COMMENT '客户联系人快照',
    phone VARCHAR(40) NULL COMMENT '客户联系电话快照',
    shipping_address VARCHAR(500) NULL COMMENT '收货地址快照',
    expected_delivery_date DATE NULL COMMENT '期望交付日期',
    status VARCHAR(32) NOT NULL COMMENT '订单状态：PENDING_APPROVAL、REJECTED、OPEN、CANCELLED',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单总金额',
    remark VARCHAR(1000) NULL COMMENT '订单备注',
    approver_user_id BIGINT UNSIGNED NOT NULL COMMENT '当前部门负责人审批人用户ID',
    approval_comment VARCHAR(1000) NULL COMMENT '部门负责人审批意见',
    approved_at DATETIME NULL COMMENT '审批通过时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '订单申请创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_sales_application (company_id, application_no),
    UNIQUE KEY uk_erp_sales_order_no (company_id, order_no),
    KEY idx_erp_sales_status (company_id, status, created_at),
    KEY idx_erp_sales_approver (company_id, approver_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP销售订单及待审批申请';

CREATE TABLE erp_sales_order_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售订单明细主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属总公司账套ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT '销售订单ID',
    line_no INT NOT NULL COMMENT '订单内明细序号',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品资料ID',
    product_code_snapshot VARCHAR(40) NOT NULL COMMENT '提交时商品编码快照',
    product_name_snapshot VARCHAR(160) NOT NULL COMMENT '提交时商品名称快照',
    quantity DECIMAL(18,4) NOT NULL COMMENT '订购数量',
    unit_price DECIMAL(18,2) NOT NULL COMMENT '订单成交单价快照',
    amount DECIMAL(18,2) NOT NULL COMMENT '明细金额快照',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_sales_line (company_id, order_id, line_no),
    KEY idx_erp_sales_line_product (company_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP销售订单商品明细';

CREATE TABLE erp_sales_order_approval (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '审批记录主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属总公司账套ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT '销售订单ID',
    approver_user_id BIGINT UNSIGNED NOT NULL COMMENT '部门负责人审批人用户ID',
    decision VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '审批决定：PENDING、APPROVED、REJECTED',
    opinion VARCHAR(1000) NULL COMMENT '审批意见或驳回原因',
    decided_at DATETIME NULL COMMENT '审批完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审批任务创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_sales_approval_order (company_id, order_id),
    KEY idx_erp_sales_approval_task (company_id, approver_user_id, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP销售订单部门负责人审批记录';

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT NULL, 'erp', 'ERP进销存', 'MODULE', 'ERP'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'erp');

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'erp'), 'erp:products', '商品资料', 'MENU', 'ERP'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'erp:products');
INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'erp'), 'erp:sales-orders', '销售订单', 'MENU', 'ERP'
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'erp:sales-orders');

INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'erp:products'), codes.permission_code, codes.permission_name, 'ACTION', 'ERP'
FROM (
    SELECT 'erp:product:view' permission_code, '查看商品资料' permission_name UNION ALL
    SELECT 'erp:product:create', '新增商品资料' UNION ALL
    SELECT 'erp:product:edit', '编辑商品资料' UNION ALL
    SELECT 'erp:product:status', '启停商品资料'
) codes;
INSERT IGNORE INTO sys_permission (parent_id, permission_code, permission_name, permission_kind, module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code = 'erp:sales-orders'), codes.permission_code, codes.permission_name, 'ACTION', 'ERP'
FROM (
    SELECT 'erp:sales-order:view' permission_code, '查看销售订单' permission_name UNION ALL
    SELECT 'erp:sales-order:create', '创建销售订单' UNION ALL
    SELECT 'erp:sales-order:approve', '审核销售订单'
) codes;
