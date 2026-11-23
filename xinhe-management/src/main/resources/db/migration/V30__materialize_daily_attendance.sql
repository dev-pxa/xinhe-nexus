ALTER TABLE oa_attendance_day_summary
    ADD COLUMN day_type VARCHAR(24) NOT NULL DEFAULT 'WORKDAY' COMMENT '日期类型：WORKDAY工作日、COMPANY_HOLIDAY公司休假、PERSONAL_HOLIDAY个人休假' AFTER punch_count,
    ADD COLUMN rule_year_id BIGINT UNSIGNED NULL COMMENT '结算采用的年度规则ID' AFTER status,
    ADD COLUMN rule_revision INT NULL COMMENT '结算采用的已发布规则版本' AFTER rule_year_id,
    ADD COLUMN calculated_at DATETIME NULL COMMENT '最近一次结算或重算时间' AFTER rule_revision;

CREATE TABLE IF NOT EXISTS oa_attendance_settlement_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日考勤结算任务ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    settlement_date DATE NOT NULL COMMENT '结算业务日期',
    status VARCHAR(16) NOT NULL COMMENT '任务状态：RUNNING、SUCCESS、FAILED',
    expected_count INT NOT NULL DEFAULT 0 COMMENT '应处理员工数',
    processed_count INT NOT NULL DEFAULT 0 COMMENT '已成功处理员工数',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '执行次数',
    error_message VARCHAR(1000) NULL COMMENT '最近一次失败原因',
    started_at DATETIME NOT NULL COMMENT '最近一次开始时间',
    finished_at DATETIME NULL COMMENT '最近一次结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_settlement_company_date (company_id, settlement_date),
    KEY idx_attendance_settlement_status (status, settlement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日考勤结算任务执行记录';
