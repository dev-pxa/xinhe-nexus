-- 销售退货申请、审核、仓库收货回补库存及统计权限。
CREATE TABLE erp_sales_return (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售退货单ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT 'ERP正式销售订单ID',
    return_no VARCHAR(40) NOT NULL COMMENT '销售退货单号',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPROVAL' COMMENT '状态：PENDING_APPROVAL、REJECTED、APPROVED、RECEIVED',
    reason VARCHAR(1000) NOT NULL COMMENT '退货原因',
    approval_opinion VARCHAR(1000) NULL COMMENT '审核意见',
    approved_by BIGINT UNSIGNED NULL COMMENT '审核人用户ID',
    approved_at DATETIME NULL COMMENT '审核时间',
    received_by BIGINT UNSIGNED NULL COMMENT '仓库收货操作人用户ID',
    received_at DATETIME NULL COMMENT '仓库收货时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_sales_return_no (company_id,return_no),
    KEY idx_erp_sales_return_order (company_id,order_id,status),
    KEY idx_erp_sales_return_status (company_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP销售退货申请及处理';

CREATE TABLE erp_sales_return_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售退货明细ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    return_id BIGINT UNSIGNED NOT NULL COMMENT '销售退货单ID',
    order_line_id BIGINT UNSIGNED NOT NULL COMMENT 'ERP正式销售订单明细ID',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '原出库仓库ID，退货收回同一仓库',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity BIGINT UNSIGNED NOT NULL COMMENT '本次退货数量',
    amount DECIMAL(18,2) NOT NULL COMMENT '按原订单单价计算的退货金额',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_sales_return_line (company_id,return_id,order_line_id),
    KEY idx_erp_sales_return_product (company_id,product_id),
    KEY idx_erp_sales_return_order_line (company_id,order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP销售退货商品明细';

INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code='erp'),'erp:sales-returns','销售退货','MENU','ERP';
INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code='erp'),'erp:sales-reports','销售统计','MENU','ERP';
INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT parent.id,codes.permission_code,codes.permission_name,'ACTION','ERP'
FROM sys_permission parent JOIN (
    SELECT 'erp:sales-returns' parent_code,'erp:sales-return:view' permission_code,'查看销售退货' permission_name UNION ALL
    SELECT 'erp:sales-returns','erp:sales-return:create','创建销售退货' UNION ALL
    SELECT 'erp:sales-returns','erp:sales-return:approve','审核销售退货' UNION ALL
    SELECT 'erp:sales-returns','erp:sales-return:receive','退货收货入库' UNION ALL
    SELECT 'erp:sales-reports','erp:sales-report:view','查看销售统计'
) codes ON codes.parent_code=parent.permission_code;
