-- CRM销售订单审批后同步ERP，补充仓库、库存与按仓库整单出库能力。
CREATE TABLE crm_sales_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'CRM销售订单主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    application_no VARCHAR(40) NOT NULL COMMENT 'CRM销售订单申请编号',
    customer_id BIGINT UNSIGNED NULL COMMENT '关联CRM客户ID，历史迁移数据可为空',
    customer_name_snapshot VARCHAR(160) NOT NULL COMMENT '客户名称快照',
    phone_snapshot VARCHAR(40) NULL COMMENT '联系电话快照',
    shipping_address_snapshot VARCHAR(500) NULL COMMENT '收货地址快照',
    salesperson_user_id BIGINT UNSIGNED NOT NULL COMMENT '销售人员用户ID',
    approval_status VARCHAR(24) NOT NULL COMMENT '审批状态：PENDING_APPROVAL、REJECTED、WITHDRAWN、APPROVED',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单总金额',
    remark VARCHAR(1000) NULL COMMENT '订单备注',
    approver_user_id BIGINT UNSIGNED NOT NULL COMMENT '部门负责人审批人用户ID',
    approval_comment VARCHAR(1000) NULL COMMENT '审批意见',
    approved_at DATETIME NULL COMMENT '审批通过时间',
    erp_order_id BIGINT UNSIGNED NULL COMMENT '同步生成的ERP正式订单ID',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sales_application (company_id, application_no),
    KEY idx_crm_sales_customer (company_id, customer_id, created_at),
    KEY idx_crm_sales_approver (company_id, approver_user_id, approval_status),
    KEY idx_crm_sales_creator (company_id, created_by, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM销售订单申请及审批';

CREATE TABLE crm_sales_order_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'CRM销售订单明细ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT 'CRM销售订单ID',
    line_no INT NOT NULL COMMENT '订单内明细序号',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品资料ID',
    product_code_snapshot VARCHAR(40) NOT NULL COMMENT '商品编码快照',
    product_name_snapshot VARCHAR(160) NOT NULL COMMENT '商品名称快照',
    category_name_snapshot VARCHAR(80) NULL COMMENT '商品分类快照',
    product_model_snapshot VARCHAR(120) NULL COMMENT '商品型号快照',
    product_specification_snapshot VARCHAR(255) NULL COMMENT '商品规格快照',
    quantity BIGINT UNSIGNED NOT NULL COMMENT '订购数量，仅允许正整数',
    unit_price DECIMAL(18,2) NOT NULL COMMENT '成交单价快照',
    amount DECIMAL(18,2) NOT NULL COMMENT '明细金额',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sales_line (company_id, order_id, line_no),
    KEY idx_crm_sales_line_product (company_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM销售订单商品明细';

CREATE TABLE crm_sales_order_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'CRM销售订单操作日志ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT 'CRM销售订单ID',
    action VARCHAR(32) NOT NULL COMMENT '操作类型',
    from_status VARCHAR(24) NULL COMMENT '操作前状态',
    to_status VARCHAR(24) NOT NULL COMMENT '操作后状态',
    opinion VARCHAR(1000) NULL COMMENT '操作意见',
    operated_by BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID',
    operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_crm_sales_log (company_id, order_id, operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CRM销售订单状态及审批日志';

CREATE TABLE erp_sales_fulfillment_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ERP正式销售订单ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    crm_sales_order_id BIGINT UNSIGNED NOT NULL COMMENT '来源CRM销售订单ID',
    order_no VARCHAR(40) NOT NULL COMMENT '正式销售订单号',
    customer_id BIGINT UNSIGNED NULL COMMENT '来源CRM客户ID',
    customer_name_snapshot VARCHAR(160) NOT NULL COMMENT '客户名称快照',
    phone_snapshot VARCHAR(40) NULL COMMENT '联系电话快照',
    shipping_address_snapshot VARCHAR(500) NULL COMMENT '收货地址快照',
    salesperson_user_id BIGINT UNSIGNED NOT NULL COMMENT '销售人员用户ID',
    fulfillment_status VARCHAR(24) NOT NULL DEFAULT 'WAITING_DISPATCH' COMMENT '履约状态：WAITING_DISPATCH、DISPATCHED、SIGNED、COMPLETED、RETURNING、RETURNED、CLOSED',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '订单总金额',
    remark VARCHAR(1000) NULL COMMENT '订单备注',
    dispatched_at DATETIME NULL COMMENT '全部仓库出库发货时间',
    signed_at DATETIME NULL COMMENT '签收时间',
    signed_by_name VARCHAR(160) NULL COMMENT '签收人名称',
    sign_remark VARCHAR(1000) NULL COMMENT '签收备注',
    completed_at DATETIME NULL COMMENT '完成时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT 'CRM审批同步操作人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_fulfillment_source (company_id, crm_sales_order_id),
    UNIQUE KEY uk_erp_fulfillment_no (company_id, order_no),
    KEY idx_erp_fulfillment_status (company_id, fulfillment_status, created_at),
    KEY idx_erp_fulfillment_customer (company_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP正式销售订单及履约状态';

CREATE TABLE erp_sales_fulfillment_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'ERP正式销售订单明细ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT 'ERP正式销售订单ID',
    line_no INT NOT NULL COMMENT '订单内明细序号',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品资料ID',
    product_code_snapshot VARCHAR(40) NOT NULL COMMENT '商品编码快照',
    product_name_snapshot VARCHAR(160) NOT NULL COMMENT '商品名称快照',
    category_name_snapshot VARCHAR(80) NULL COMMENT '商品分类快照',
    product_model_snapshot VARCHAR(120) NULL COMMENT '商品型号快照',
    product_specification_snapshot VARCHAR(255) NULL COMMENT '商品规格快照',
    quantity BIGINT UNSIGNED NOT NULL COMMENT '订购数量',
    unit_price DECIMAL(18,2) NOT NULL COMMENT '成交单价快照',
    amount DECIMAL(18,2) NOT NULL COMMENT '明细金额',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_fulfillment_line (company_id, order_id, line_no),
    KEY idx_erp_fulfillment_product (company_id, product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP正式销售订单商品快照';

CREATE TABLE erp_warehouse (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    warehouse_code VARCHAR(40) NOT NULL COMMENT '公司内唯一仓库编号',
    warehouse_name VARCHAR(120) NOT NULL COMMENT '仓库名称',
    address VARCHAR(500) NULL COMMENT '仓库地址',
    remark VARCHAR(1000) NULL COMMENT '仓库备注',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED、DISABLED',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    deleted_at DATETIME NULL COMMENT '软删除时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_warehouse_code (company_id, warehouse_code),
    KEY idx_erp_warehouse_status (company_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP仓库资料';

CREATE TABLE erp_inventory_balance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '库存余额ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库ID',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity BIGINT NOT NULL DEFAULT 0 COMMENT '当前库存数量',
    version INT NOT NULL DEFAULT 0 COMMENT '并发控制版本号',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变更时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_inventory_balance (company_id, warehouse_id, product_id),
    KEY idx_erp_inventory_product (company_id, product_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每个仓库每个商品的库存余额';

CREATE TABLE erp_inventory_transaction (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '库存流水ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '仓库ID',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    transaction_type VARCHAR(24) NOT NULL COMMENT '流水类型：ADJUST、SALES_DISPATCH、SALES_RETURN',
    quantity_change BIGINT NOT NULL COMMENT '库存变动数量，出库为负数',
    balance_after BIGINT NOT NULL COMMENT '变动后库存',
    source_type VARCHAR(32) NOT NULL COMMENT '来源单据类型',
    source_id BIGINT UNSIGNED NULL COMMENT '来源单据ID',
    reason VARCHAR(1000) NULL COMMENT '调整原因或备注',
    operated_by BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID',
    operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_erp_inventory_txn_product (company_id, warehouse_id, product_id, operated_at),
    KEY idx_erp_inventory_txn_source (company_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP库存变动流水';

CREATE TABLE erp_sales_dispatch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售出库发货单ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    order_id BIGINT UNSIGNED NOT NULL COMMENT 'ERP正式销售订单ID',
    warehouse_id BIGINT UNSIGNED NOT NULL COMMENT '本单出库仓库ID',
    dispatch_no VARCHAR(40) NOT NULL COMMENT '销售出库发货单号，每个仓库一张',
    carrier VARCHAR(120) NULL COMMENT '承运商或配送方式',
    tracking_no VARCHAR(120) NULL COMMENT '物流单号',
    remark VARCHAR(1000) NULL COMMENT '出库发货备注',
    dispatched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '出库发货时间',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '操作人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_dispatch_no (company_id, dispatch_no),
    UNIQUE KEY uk_erp_dispatch_order_warehouse (company_id, order_id, warehouse_id),
    KEY idx_erp_dispatch_order (company_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='按仓库生成的销售出库发货单';

CREATE TABLE erp_sales_dispatch_line (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '销售出库发货单明细ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    dispatch_id BIGINT UNSIGNED NOT NULL COMMENT '出库发货单ID',
    order_line_id BIGINT UNSIGNED NOT NULL COMMENT 'ERP正式销售订单明细ID',
    product_id BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
    quantity BIGINT UNSIGNED NOT NULL COMMENT '整行出库数量',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_dispatch_order_line (company_id, order_line_id),
    KEY idx_erp_dispatch_line_parent (company_id, dispatch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库发货单商品明细';

-- 迁移既有订单申请到CRM；既有正式订单同步为ERP履约订单。
INSERT INTO crm_sales_order(id,company_id,application_no,customer_id,customer_name_snapshot,phone_snapshot,shipping_address_snapshot,salesperson_user_id,approval_status,total_amount,remark,approver_user_id,approval_comment,approved_at,version,created_by,updated_by,created_at,updated_at)
SELECT id,company_id,application_no,NULL,customer_name,phone,shipping_address,created_by,
       CASE status WHEN 'OPEN' THEN 'APPROVED' WHEN 'CANCELLED' THEN 'WITHDRAWN' ELSE status END,
       total_amount,remark,approver_user_id,approval_comment,approved_at,version,created_by,updated_by,created_at,updated_at
FROM erp_sales_order;

INSERT INTO crm_sales_order_line(id,company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount)
SELECT id,company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,CAST(quantity AS UNSIGNED),unit_price,amount
FROM erp_sales_order_line;

INSERT INTO erp_sales_fulfillment_order(id,company_id,crm_sales_order_id,order_no,customer_id,customer_name_snapshot,phone_snapshot,shipping_address_snapshot,salesperson_user_id,fulfillment_status,total_amount,remark,dispatched_at,signed_at,completed_at,version,created_by,updated_by,created_at,updated_at)
SELECT id,company_id,id,order_no,NULL,customer_name,phone,shipping_address,created_by,'WAITING_DISPATCH',total_amount,remark,NULL,NULL,NULL,version,created_by,updated_by,approved_at,updated_at
FROM erp_sales_order WHERE status='OPEN' AND order_no IS NOT NULL;

INSERT INTO erp_sales_fulfillment_line(company_id,order_id,line_no,product_id,product_code_snapshot,product_name_snapshot,category_name_snapshot,product_model_snapshot,product_specification_snapshot,quantity,unit_price,amount)
SELECT l.company_id,l.order_id,l.line_no,l.product_id,l.product_code_snapshot,l.product_name_snapshot,l.category_name_snapshot,l.product_model_snapshot,l.product_specification_snapshot,CAST(l.quantity AS UNSIGNED),l.unit_price,l.amount
FROM erp_sales_order_line l JOIN erp_sales_order o ON o.id=l.order_id AND o.company_id=l.company_id
WHERE o.status='OPEN' AND o.order_no IS NOT NULL;

UPDATE crm_sales_order c JOIN erp_sales_fulfillment_order e ON e.company_id=c.company_id AND e.crm_sales_order_id=c.id SET c.erp_order_id=e.id;

INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code='crm:customers'),codes.permission_code,codes.permission_name,'ACTION','CRM'
FROM (
    SELECT 'crm:sales-order:view' permission_code,'查看客户销售订单' permission_name UNION ALL
    SELECT 'crm:sales-order:create','创建客户销售订单' UNION ALL
    SELECT 'crm:sales-order:edit','编辑客户销售订单' UNION ALL
    SELECT 'crm:sales-order:withdraw','撤回客户销售订单' UNION ALL
    SELECT 'crm:sales-order:approve','审核客户销售订单'
) codes;

INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code='erp'),'erp:warehouses','仓库管理','MENU','ERP';
INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT (SELECT id FROM sys_permission WHERE permission_code='erp'),'erp:inventory','库存管理','MENU','ERP';

INSERT IGNORE INTO sys_permission(parent_id,permission_code,permission_name,permission_kind,module_code)
SELECT parent.id,codes.permission_code,codes.permission_name,'ACTION','ERP'
FROM sys_permission parent JOIN (
    SELECT 'erp:warehouses' parent_code,'erp:warehouse:view' permission_code,'查看仓库' permission_name UNION ALL
    SELECT 'erp:warehouses','erp:warehouse:edit','维护仓库' UNION ALL
    SELECT 'erp:inventory','erp:inventory:view','查看库存' UNION ALL
    SELECT 'erp:inventory','erp:inventory:adjust','调整库存' UNION ALL
    SELECT 'erp:sales-orders','erp:sales-dispatch:confirm','确认销售出库发货' UNION ALL
    SELECT 'erp:sales-orders','erp:sales-order:sign','确认订单签收' UNION ALL
    SELECT 'erp:sales-orders','erp:sales-order:complete','完成销售订单'
) codes ON codes.parent_code=parent.permission_code;

-- 兼容原有岗位配置：原销售订单权限自动映射到新的CRM申请和ERP履约权限。
INSERT IGNORE INTO sys_position_permission(company_id,position_id,permission_id,created_by)
SELECT oldpp.company_id,oldpp.position_id,newp.id,oldpp.created_by FROM sys_position_permission oldpp
JOIN sys_permission oldp ON oldp.id=oldpp.permission_id
JOIN (
    SELECT 'erp:sales-order:create' old_code,'crm:sales-order:create' new_code UNION ALL
    SELECT 'erp:sales-order:create','crm:sales-order:view' UNION ALL
    SELECT 'erp:sales-order:create','crm:sales-order:edit' UNION ALL
    SELECT 'erp:sales-order:create','crm:sales-order:withdraw' UNION ALL
    SELECT 'erp:sales-order:approve','crm:sales-order:approve' UNION ALL
    SELECT 'erp:sales-order:approve','crm:sales-order:view' UNION ALL
    SELECT 'erp:sales-order:view','erp:sales-order:view'
) mapping ON mapping.old_code=oldp.permission_code
JOIN sys_permission newp ON newp.permission_code=mapping.new_code;
