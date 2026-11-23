ALTER TABLE oa_attendance_user_rule_config
    ADD COLUMN implicit_holiday_credit INT NULL COMMENT '首次配置时自动折算的历史已休天数' AFTER user_id,
    ADD COLUMN baseline_date DATE NULL COMMENT '历史自动折算截止日期，后续保持不变' AFTER implicit_holiday_credit;

CREATE TEMPORARY TABLE tmp_attendance_rule_baseline AS
SELECT rule_year_id, user_id, MIN(configured_at) AS first_configured_at
FROM oa_attendance_user_rule_config
GROUP BY rule_year_id, user_id;

UPDATE oa_attendance_user_rule_config cfg
JOIN oa_attendance_rule_year r ON r.id = cfg.rule_year_id
JOIN tmp_attendance_rule_baseline first_config
    ON first_config.rule_year_id = cfg.rule_year_id AND first_config.user_id = cfg.user_id
SET cfg.baseline_date = DATE(first_config.first_configured_at),
    cfg.implicit_holiday_credit = CASE
        WHEN r.rule_year < YEAR(first_config.first_configured_at) THEN FLOOR(DAYOFYEAR(CONCAT(r.rule_year, '-12-31')) / 7)
        WHEN r.rule_year > YEAR(first_config.first_configured_at) THEN 0
        ELSE FLOOR(DAYOFYEAR(first_config.first_configured_at) / 7)
    END;

DROP TEMPORARY TABLE tmp_attendance_rule_baseline;

ALTER TABLE oa_attendance_user_rule_config
    MODIFY COLUMN implicit_holiday_credit INT NOT NULL COMMENT '首次配置时自动折算的历史已休天数',
    MODIFY COLUMN baseline_date DATE NOT NULL COMMENT '历史自动折算截止日期，后续保持不变';
