ALTER TABLE erp_sales_return
    ADD COLUMN previous_fulfillment_status VARCHAR(24) NULL COMMENT '创建退货前的订单履约状态' AFTER order_id;

UPDATE oa_attendance_correction_request older
JOIN oa_attendance_correction_request newer
  ON newer.company_id = older.company_id
 AND newer.user_id = older.user_id
 AND newer.attendance_date = older.attendance_date
 AND newer.status = 'PENDING'
 AND newer.id < older.id
SET older.status = 'CANCELLED',
    older.review_comment = COALESCE(older.review_comment, '系统迁移：重复待审批申请已取消')
WHERE older.status = 'PENDING';

ALTER TABLE oa_attendance_correction_request
    ADD COLUMN pending_request_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'PENDING' THEN CONCAT(user_id, ':', attendance_date) ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_attendance_correction_pending (company_id, pending_request_key);
