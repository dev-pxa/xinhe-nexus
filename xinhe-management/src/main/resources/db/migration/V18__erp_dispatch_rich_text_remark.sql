-- 出库发货备注支持净化后的富文本 HTML。
ALTER TABLE erp_sales_dispatch
    MODIFY COLUMN remark TEXT NULL COMMENT '出库发货备注（净化后的富文本HTML）';
