-- CRM销售订单审批意见改为富文本，放宽 HTML 格式标记占用的存储空间。
ALTER TABLE crm_sales_order
    MODIFY COLUMN approval_comment TEXT NULL COMMENT '审批意见（净化后的富文本HTML）';

ALTER TABLE crm_sales_order_log
    MODIFY COLUMN opinion TEXT NULL COMMENT '操作意见（净化后的富文本HTML）';
