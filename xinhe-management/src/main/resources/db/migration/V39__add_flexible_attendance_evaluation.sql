ALTER TABLE oa_attendance_time_range ADD COLUMN flexible_minutes INT NOT NULL DEFAULT 30;
ALTER TABLE oa_attendance_day_summary
    ADD COLUMN is_late TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN is_early TINYINT NOT NULL DEFAULT 0;
