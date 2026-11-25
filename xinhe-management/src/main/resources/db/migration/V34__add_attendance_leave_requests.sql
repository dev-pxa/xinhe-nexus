CREATE TABLE oa_attendance_leave_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    applicant_id BIGINT UNSIGNED NOT NULL,
    applicant_name VARCHAR(64) NOT NULL,
    department_name VARCHAR(128) NOT NULL,
    leave_type VARCHAR(24) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_MANAGER',
    manager_id BIGINT UNSIGNED NOT NULL,
    manager_name VARCHAR(64) NOT NULL,
    manager_comment VARCHAR(1000) NULL,
    manager_acted_at DATETIME NULL,
    final_reviewer_id BIGINT UNSIGNED NOT NULL,
    final_reviewer_name VARCHAR(64) NOT NULL,
    final_comment VARCHAR(1000) NULL,
    final_acted_at DATETIME NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_leave_applicant (company_id, applicant_id, id),
    KEY idx_leave_manager (company_id, manager_id, id),
    KEY idx_leave_final_reviewer (company_id, final_reviewer_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全天请假申请及两级审批';

CREATE TABLE oa_attendance_leave_day (
    request_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    applicant_id BIGINT UNSIGNED NOT NULL,
    leave_date DATE NOT NULL,
    day_type VARCHAR(24) NOT NULL,
    reserved TINYINT NULL COMMENT '申请中或已获批的工作日为1，其他日期为空',
    PRIMARY KEY (request_id, leave_date),
    UNIQUE KEY uk_leave_reserved_day (company_id, applicant_id, leave_date, reserved)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='请假日期明细与重叠约束';

CREATE TABLE oa_attendance_leave_attachment (
    request_id BIGINT UNSIGNED NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_data MEDIUMBLOB NOT NULL,
    PRIMARY KEY (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='请假证明附件';

ALTER TABLE oa_attendance_day_summary ADD COLUMN leave_type VARCHAR(24) NULL;
