CREATE TABLE IF NOT EXISTS oa_attendance_user_rule_config (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '员工年度考勤配置ID',
    rule_year_id BIGINT UNSIGNED NOT NULL COMMENT '年度规则ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '公司ID',
    revision INT NOT NULL COMMENT '规则版本',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '员工用户ID',
    configured_by BIGINT UNSIGNED NOT NULL COMMENT '配置操作人ID',
    configured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_user_rule_config (rule_year_id, revision, user_id),
    KEY idx_attendance_user_rule_company (company_id, user_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工年度考勤规则配置状态';

INSERT IGNORE INTO oa_attendance_user_rule_config
    (rule_year_id, company_id, revision, user_id, configured_by, configured_at)
SELECT h.rule_year_id, h.company_id, h.revision, h.user_id, MIN(h.created_by), MIN(h.created_at)
FROM oa_attendance_user_holiday h
GROUP BY h.rule_year_id, h.company_id, h.revision, h.user_id;
