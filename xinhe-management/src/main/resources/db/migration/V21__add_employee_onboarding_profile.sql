CREATE TABLE IF NOT EXISTS hr_onboarding_invitation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    candidate_name VARCHAR(64) NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    position_id BIGINT UNSIGNED NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME NOT NULL,
    submitted_at DATETIME NULL,
    created_by BIGINT UNSIGNED NULL,
    revoked_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_onboarding_token (token_hash),
    KEY idx_hr_onboarding_company_status (company_id, status),
    KEY idx_hr_onboarding_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入职资料一次性采集表单';

CREATE TABLE IF NOT EXISTS hr_employee_profile (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    invitation_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    candidate_name VARCHAR(64) NOT NULL,
    gender VARCHAR(16) NULL,
    birth_date DATE NULL,
    id_type VARCHAR(32) NULL,
    id_number_ciphertext TEXT NULL,
    id_number_hash CHAR(64) NULL,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    address_ciphertext TEXT NULL,
    emergency_contact_name VARCHAR(64) NULL,
    emergency_contact_relation VARCHAR(32) NULL,
    emergency_contact_phone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'LOCKED',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_profile_invitation (invitation_id),
    UNIQUE KEY uk_hr_profile_user (user_id),
    KEY idx_hr_profile_company (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人员档案';

CREATE TABLE IF NOT EXISTS hr_employee_education (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    profile_id BIGINT UNSIGNED NOT NULL,
    start_month CHAR(7) NOT NULL,
    end_month CHAR(7) NOT NULL,
    education_level VARCHAR(32) NOT NULL,
    school_name VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_hr_education_profile (profile_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人员教育背景';
