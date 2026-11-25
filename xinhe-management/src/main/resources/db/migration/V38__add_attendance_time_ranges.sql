CREATE TABLE oa_attendance_time_range (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_year_id BIGINT UNSIGNED NOT NULL,
    revision INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    KEY idx_time_range_rule (rule_year_id, revision, start_date)
) ENGINE=InnoDB COMMENT='年度考勤时间范围';
