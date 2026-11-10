ALTER TABLE oa_attendance_correction_request
    ADD COLUMN location_address VARCHAR(500) NULL COMMENT '申请定位地址' AFTER location_source;
