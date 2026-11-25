ALTER TABLE oa_attendance_leave_request
    MODIFY COLUMN final_reviewer_id BIGINT UNSIGNED NULL,
    MODIFY COLUMN final_reviewer_name VARCHAR(64) NULL;
