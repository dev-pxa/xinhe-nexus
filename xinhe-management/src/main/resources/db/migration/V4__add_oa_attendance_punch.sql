CREATE TABLE IF NOT EXISTS oa_attendance_punch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '打卡记录主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '打卡用户ID',
    punch_date DATE NOT NULL COMMENT '打卡日期，按系统时区计算',
    punch_time DATETIME NOT NULL COMMENT '实际打卡时间，按系统时区保存',
    punch_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '打卡类型：NORMAL普通打卡',
    source VARCHAR(16) NOT NULL DEFAULT 'WEB' COMMENT '打卡来源：WEB网页、MOBILE移动端、ADMIN管理员补录、IMPORT导入',
    client_ip VARCHAR(64) NULL COMMENT '客户端IP地址',
    user_agent VARCHAR(512) NULL COMMENT '客户端User-Agent',
    remark VARCHAR(255) NULL COMMENT '打卡备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (id),
    KEY idx_attendance_punch_user_date (company_id, user_id, punch_date, punch_time),
    KEY idx_attendance_punch_date (company_id, punch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA原始打卡记录表';

CREATE TABLE IF NOT EXISTS oa_attendance_day_summary (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '每日考勤汇总主键ID',
    company_id BIGINT UNSIGNED NOT NULL COMMENT '所属公司ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    punch_date DATE NOT NULL COMMENT '汇总日期，按系统时区计算',
    first_punch_time DATETIME NULL COMMENT '当天最早打卡时间',
    last_punch_time DATETIME NULL COMMENT '当天最晚打卡时间',
    punch_count INT NOT NULL DEFAULT 0 COMMENT '当天打卡次数',
    status VARCHAR(16) NOT NULL DEFAULT 'NO_RECORD' COMMENT '汇总状态：NO_RECORD无记录、SINGLE单次、NORMAL两次及以上',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_day_user (company_id, user_id, punch_date),
    KEY idx_attendance_day_date (company_id, punch_date),
    KEY idx_attendance_day_user (company_id, user_id, punch_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='OA每日打卡汇总表';

INSERT IGNORE INTO sys_permission (permission_code, permission_name, permission_kind, module_code)
VALUES
    ('oa', 'OA协同办公', 'MODULE', 'OA'),
    ('oa:attendance', '打卡记录', 'MENU', 'OA'),
    ('oa:attendance:punch', '打卡', 'ACTION', 'OA'),
    ('oa:attendance:self:view', '查看我的打卡记录', 'ACTION', 'OA'),
    ('oa:attendance:all:view', '查看全部打卡记录', 'ACTION', 'OA'),
    ('oa:attendance:export', '导出打卡记录', 'ACTION', 'OA');
