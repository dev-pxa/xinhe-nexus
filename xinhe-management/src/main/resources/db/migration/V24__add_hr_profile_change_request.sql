CREATE TABLE IF NOT EXISTS hr_employee_profile_change_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    profile_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    applicant_user_id BIGINT UNSIGNED NOT NULL,
    approver_user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、APPROVED、REJECTED、CANCELLED',
    before_data JSON NOT NULL,
    after_data JSON NOT NULL,
    review_comment VARCHAR(1000) NULL,
    reviewed_by BIGINT UNSIGNED NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_hr_profile_change_user (company_id, applicant_user_id, status, created_at),
    KEY idx_hr_profile_change_approver (company_id, approver_user_id, status, created_at),
    KEY idx_hr_profile_change_profile (company_id, profile_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人员档案变更申请';

INSERT IGNORE INTO sys_permission (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('hr:profile:view', '查看个人档案', 'ACTION', 'OA'),
    ('hr:profile:submit', '提交人员档案变更申请', 'ACTION', 'OA'),
    ('hr:profile:approve', '审批人员档案变更', 'ACTION', 'OA');
