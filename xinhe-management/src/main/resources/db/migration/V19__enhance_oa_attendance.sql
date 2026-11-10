-- OA 考勤基础增强：定位、补录审计、补打卡申请与细粒度权限。
ALTER TABLE oa_attendance_punch
    ADD COLUMN operator_user_id BIGINT UNSIGNED NULL COMMENT '实际操作人ID，普通打卡为空，管理员补录或审批落地时记录' AFTER user_id,
    ADD COLUMN correction_request_id BIGINT UNSIGNED NULL COMMENT '来源补打卡申请ID' AFTER operator_user_id,
    ADD COLUMN latitude DECIMAL(10,7) NULL COMMENT '打卡纬度' AFTER user_agent,
    ADD COLUMN longitude DECIMAL(10,7) NULL COMMENT '打卡经度' AFTER latitude,
    ADD COLUMN location_accuracy DECIMAL(8,2) NULL COMMENT '定位精度，单位米' AFTER longitude,
    ADD COLUMN location_source VARCHAR(24) NULL COMMENT '定位来源：BROWSER_GPS、MOBILE_GPS、ADMIN_ENTRY、NONE' AFTER location_accuracy,
    ADD COLUMN client_captured_at DATETIME NULL COMMENT '客户端采集定位时间' AFTER location_source,
    ADD COLUMN timezone VARCHAR(64) NULL COMMENT '打卡业务时区' AFTER client_captured_at,
    ADD KEY idx_attendance_punch_correction (company_id, correction_request_id);

CREATE TABLE IF NOT EXISTS oa_attendance_correction_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '补打卡申请主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '申请员工ID',
    approver_user_id BIGINT UNSIGNED NOT NULL COMMENT '直属负责人审批人ID',
    attendance_date DATE NOT NULL COMMENT '申请考勤日期',
    punch_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '申请打卡类型：IN、OUT、NORMAL',
    requested_punch_time DATETIME NOT NULL COMMENT '申请补打卡时间',
    latitude DECIMAL(10,7) NULL COMMENT '申请定位纬度',
    longitude DECIMAL(10,7) NULL COMMENT '申请定位经度',
    location_accuracy DECIMAL(8,2) NULL COMMENT '申请定位精度，单位米',
    location_source VARCHAR(24) NULL COMMENT '定位来源',
    reason VARCHAR(1000) NOT NULL COMMENT '补打卡原因',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING、APPROVED、REJECTED、CANCELLED',
    review_comment VARCHAR(1000) NULL COMMENT '直属负责人审批意见',
    reviewed_by BIGINT UNSIGNED NULL COMMENT '审批人ID',
    reviewed_at DATETIME NULL COMMENT '审批时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_attendance_correction_user (company_id, user_id, status, created_at),
    KEY idx_attendance_correction_approver (company_id, approver_user_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA补打卡申请';

INSERT IGNORE INTO sys_permission (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('oa:attendance:admin-punch', '管理员补打卡', 'ACTION', 'OA'),
    ('oa:attendance:correction:apply', '发起补打卡申请', 'ACTION', 'OA'),
    ('oa:attendance:correction:view', '查看补打卡申请', 'ACTION', 'OA'),
    ('oa:attendance:correction:review', '审批补打卡申请', 'ACTION', 'OA');
