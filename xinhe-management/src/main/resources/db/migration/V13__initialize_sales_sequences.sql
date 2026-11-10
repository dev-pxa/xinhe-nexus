-- 为迁移后的正式订单初始化公司级编号序列，避免与历史SO编号冲突。
INSERT INTO erp_number_sequence(company_id,document_type,current_value)
SELECT company_id,'SALES_ORDER',COALESCE(MAX(CAST(SUBSTRING(order_no,4) AS UNSIGNED)),0)
FROM erp_sales_fulfillment_order GROUP BY company_id
ON DUPLICATE KEY UPDATE current_value=GREATEST(current_value,VALUES(current_value));
