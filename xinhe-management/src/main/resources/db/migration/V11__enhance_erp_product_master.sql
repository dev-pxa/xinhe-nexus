-- 完善 ERP 商品主数据：商品分类、型号、规格、独立查询字段和订单快照。
CREATE TABLE erp_number_sequence (
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    document_type VARCHAR(32) NOT NULL COMMENT '编号类型：PRODUCT商品、PRODUCT_CATEGORY商品分类',
    current_value BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前已使用序号',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (company_id, document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP公司级业务编号序列表';

CREATE TABLE erp_product_category (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品分类主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属总公司账套ID',
    parent_id BIGINT UNSIGNED NULL COMMENT '上级分类ID，为空表示一级分类',
    category_code VARCHAR(40) NOT NULL COMMENT '公司内唯一分类编码',
    category_name VARCHAR(80) NOT NULL COMMENT '分类名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '同级分类显示顺序',
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '分类状态：ENABLED启用、DISABLED停用',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_by BIGINT UNSIGNED NULL COMMENT '创建人用户ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_category_code (company_id, category_code),
    KEY idx_erp_category_parent (company_id, parent_id, status, sort_order),
    KEY idx_erp_category_name (company_id, category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ERP商品分类表';

INSERT INTO erp_product_category(company_id,parent_id,category_code,category_name,sort_order,status)
SELECT id,NULL,'CAT-0000','未分类',0,'ENABLED'
FROM sys_company
WHERE deleted_at IS NULL;

ALTER TABLE erp_product
    ADD COLUMN category_id BIGINT UNSIGNED NULL COMMENT '商品分类ID' AFTER product_name,
    ADD COLUMN model VARCHAR(120) NULL COMMENT '商品型号' AFTER category_id,
    ADD COLUMN specification VARCHAR(255) NULL COMMENT '商品规格' AFTER model,
    ADD KEY idx_erp_product_category (company_id, category_id, status),
    ADD KEY idx_erp_product_model (company_id, model),
    ADD KEY idx_erp_product_specification (company_id, specification);

UPDATE erp_product p
JOIN erp_product_category c ON c.company_id=p.company_id AND c.category_code='CAT-0000'
SET p.category_id=c.id
WHERE p.category_id IS NULL;

ALTER TABLE erp_product
    MODIFY COLUMN category_id BIGINT UNSIGNED NOT NULL COMMENT '商品分类ID';

-- 将首期八位编码统一收敛为至少四位编码，例如 PRD-00000001 改为 PRD-0001。
UPDATE erp_product
SET product_code=CONCAT('PRD-',LPAD(CAST(SUBSTRING_INDEX(product_code,'-',-1) AS UNSIGNED),4,'0'))
WHERE product_code REGEXP '^PRD-[0-9]+$';

INSERT INTO erp_number_sequence(company_id,document_type,current_value)
SELECT company_id,'PRODUCT',MAX(CAST(SUBSTRING_INDEX(product_code,'-',-1) AS UNSIGNED))
FROM erp_product
GROUP BY company_id;

INSERT INTO erp_number_sequence(company_id,document_type,current_value)
SELECT company_id,'PRODUCT_CATEGORY',0
FROM erp_product_category
GROUP BY company_id;

ALTER TABLE erp_sales_order_line
    ADD COLUMN category_name_snapshot VARCHAR(80) NULL COMMENT '提交时商品分类名称快照' AFTER product_name_snapshot,
    ADD COLUMN product_model_snapshot VARCHAR(120) NULL COMMENT '提交时商品型号快照' AFTER category_name_snapshot,
    ADD COLUMN product_specification_snapshot VARCHAR(255) NULL COMMENT '提交时商品规格快照' AFTER product_model_snapshot;

UPDATE erp_sales_order_line l
JOIN erp_product p ON p.id=l.product_id AND p.company_id=l.company_id
JOIN erp_product_category c ON c.id=p.category_id AND c.company_id=p.company_id
SET l.category_name_snapshot=c.category_name,
    l.product_model_snapshot=p.model,
    l.product_specification_snapshot=p.specification
WHERE l.category_name_snapshot IS NULL;

ALTER TABLE erp_sales_order_line
    MODIFY COLUMN category_name_snapshot VARCHAR(80) NOT NULL COMMENT '提交时商品分类名称快照';
