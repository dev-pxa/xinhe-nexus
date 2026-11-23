CREATE TABLE IF NOT EXISTS oa_attendance_rule_year (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '年度规则ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    rule_year SMALLINT NOT NULL COMMENT '规则年份',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT草稿、PUBLISHED已发布',
    company_configured TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已设置公司放假',
    draft_revision INT NOT NULL DEFAULT 1 COMMENT '当前草稿版本',
    published_revision INT NULL COMMENT '当前已发布版本',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    published_by BIGINT UNSIGNED NULL COMMENT '发布人ID',
    published_at DATETIME NULL COMMENT '发布时间',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by BIGINT UNSIGNED NOT NULL COMMENT '最后修改人ID',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_rule_company_year (company_id, rule_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='年度考勤规则';

CREATE TABLE IF NOT EXISTS oa_attendance_company_holiday (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '公司放假ID',
    rule_year_id BIGINT UNSIGNED NOT NULL COMMENT '年度规则ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    revision INT NOT NULL COMMENT '规则版本',
    holiday_date DATE NOT NULL COMMENT '放假日期',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_holiday_revision (rule_year_id, revision, holiday_date),
    KEY idx_company_holiday_date (company_id, holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公司放假日期';

CREATE TABLE IF NOT EXISTS oa_attendance_user_holiday (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '个人放假ID',
    rule_year_id BIGINT UNSIGNED NOT NULL COMMENT '年度规则ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    revision INT NOT NULL COMMENT '规则版本',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '员工用户ID',
    holiday_date DATE NOT NULL COMMENT '放假日期',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_holiday_revision (rule_year_id, revision, user_id, holiday_date),
    KEY idx_user_holiday_date (company_id, user_id, holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工个人放假日期';

CREATE TABLE IF NOT EXISTS oa_attendance_rule_change_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '变更日志ID',
    rule_year_id BIGINT UNSIGNED NOT NULL COMMENT '年度规则ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    revision INT NOT NULL COMMENT '规则版本',
    action VARCHAR(32) NOT NULL COMMENT '动作：SAVE_COMPANY、SAVE_USER、PUBLISH',
    target_user_id BIGINT UNSIGNED NULL COMMENT '目标员工用户ID',
    detail_json JSON NULL COMMENT '变更详情',
    operator_user_id BIGINT UNSIGNED NOT NULL COMMENT '操作人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_attendance_rule_log (rule_year_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='考勤规则变更日志';
