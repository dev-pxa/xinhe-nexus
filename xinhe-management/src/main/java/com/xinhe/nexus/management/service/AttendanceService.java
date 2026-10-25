package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AttendanceService {
    private static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    public AttendanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> punch(ManagementUser user, String ip, String userAgent, String remark) {
        ensureCompanyUser(user);
        LocalDateTime now = LocalDateTime.now(SYSTEM_ZONE).withNano(0);
        LocalDate date = now.toLocalDate();
        jdbcTemplate.update(
                """
                INSERT INTO oa_attendance_punch
                    (company_id, user_id, punch_date, punch_time, punch_type, source, client_ip, user_agent, remark)
                VALUES (?, ?, ?, ?, 'NORMAL', 'WEB', ?, ?, ?)
                """,
                user.companyId(), user.id(), date, now, ip, truncate(userAgent, 512), truncate(remark, 255));
        refreshSummary(user.companyId(), user.id(), date);
        return findSummary(user.companyId(), user.id(), date);
    }

    public List<Map<String, Object>> listMine(ManagementUser user, LocalDate from, LocalDate to) {
        ensureCompanyUser(user);
        validateRange(from, to);
        return listSummaries(user.companyId(), user.id(), from, to, null);
    }

    public List<Map<String, Object>> listAll(
            ManagementUser user, Long companyId, LocalDate from, LocalDate to, Long userId, Long orgUnitId, String keyword) {
        ensureAdmin(user);
        validateRange(from, to);
        Long scopeCompany = "PLATFORM_ADMIN".equals(role(user)) ? companyId : user.companyId();
        if (!"PLATFORM_ADMIN".equals(role(user)) && companyId != null && !companyId.equals(user.companyId())) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "不能查看其他公司的打卡记录");
        }
        return listSummaries(scopeCompany, userId, from, to, new Filter(orgUnitId, keyword));
    }

    public Map<String, Object> adminPunch(
            ManagementUser operator, Long companyId, long userId, String punchTime, String ip, String userAgent, String remark) {
        ensureAdmin(operator);
        Long targetCompany = "PLATFORM_ADMIN".equals(role(operator)) ? companyId : operator.companyId();
        if (targetCompany == null) {
            throw new IllegalArgumentException("请选择公司");
        }
        if (!"PLATFORM_ADMIN".equals(role(operator)) && !targetCompany.equals(operator.companyId())) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "不能为其他公司录入打卡");
        }
        Integer enabled = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM sys_user_company uc
                JOIN sys_user u ON u.id = uc.user_id AND u.deleted_at IS NULL AND u.status = 'ENABLED'
                WHERE uc.company_id = ? AND uc.user_id = ? AND uc.status = 'ENABLED' AND uc.deleted_at IS NULL
                """, Integer.class, targetCompany, userId);
        if (enabled == null || enabled == 0) {
            throw new IllegalArgumentException("指定用户不属于当前公司或账号已停用");
        }
        LocalDateTime parsedTime = parsePunchTime(punchTime);
        jdbcTemplate.update(
                """
                INSERT INTO oa_attendance_punch
                    (company_id, user_id, punch_date, punch_time, punch_type, source, client_ip, user_agent, remark)
                VALUES (?, ?, ?, ?, 'NORMAL', 'ADMIN', ?, ?, ?)
                """, targetCompany, userId, parsedTime.toLocalDate(), parsedTime,
                ip, truncate(userAgent, 512), truncate(remark, 255));
        refreshSummary(targetCompany, userId, parsedTime.toLocalDate());
        return findSummary(targetCompany, userId, parsedTime.toLocalDate());
    }

    private List<Map<String, Object>> listSummaries(
            Long companyId, Long userId, LocalDate from, LocalDate to, Filter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.company_id AS companyId, s.user_id AS userId,
                       u.username, u.display_name AS displayName, MAX(o.org_name) AS orgName,
                       DATE_FORMAT(s.punch_date, '%Y-%m-%d') AS punchDate, s.first_punch_time AS firstPunchTime,
                       s.last_punch_time AS lastPunchTime, s.punch_count AS punchCount, s.status
                FROM oa_attendance_day_summary s
                JOIN sys_user u ON u.id = s.user_id AND u.deleted_at IS NULL
                LEFT JOIN sys_user_company uc ON uc.user_id = s.user_id
                    AND uc.company_id = s.company_id AND uc.deleted_at IS NULL
                LEFT JOIN sys_user_position up ON up.user_id = s.user_id
                    AND up.company_id = s.company_id AND up.status = 'ENABLED' AND up.deleted_at IS NULL
                LEFT JOIN sys_position p ON p.id = up.position_id
                    AND p.company_id = s.company_id AND p.status = 'ENABLED' AND p.deleted_at IS NULL
                LEFT JOIN sys_org_unit o ON o.id = p.org_unit_id
                    AND o.company_id = s.company_id AND o.deleted_at IS NULL
                WHERE s.punch_date BETWEEN ? AND ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(from);
        args.add(to);
        if (companyId != null) {
            sql.append(" AND s.company_id = ?");
            args.add(companyId);
        }
        if (userId != null) {
            sql.append(" AND s.user_id = ?");
            args.add(userId);
        }
        if (filter != null && filter.orgUnitId() != null) {
            sql.append(" AND o.id = ?");
            args.add(filter.orgUnitId());
        }
        if (filter != null && filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND (u.username LIKE ? OR u.display_name LIKE ?)");
            String keyword = "%" + filter.keyword().trim() + "%";
            args.add(keyword);
            args.add(keyword);
        }
        sql.append(" GROUP BY s.id, s.company_id, s.user_id, u.username, u.display_name,");
        sql.append(" s.punch_date, s.first_punch_time, s.last_punch_time, s.punch_count, s.status");
        sql.append(" ORDER BY s.punch_date DESC, u.display_name, s.user_id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    private void refreshSummary(long companyId, long userId, LocalDate date) {
        jdbcTemplate.update(
                """
                INSERT INTO oa_attendance_day_summary
                    (company_id, user_id, punch_date, first_punch_time, last_punch_time, punch_count, status)
                SELECT ?, ?, ?, MIN(punch_time), MAX(punch_time), COUNT(*),
                       CASE WHEN COUNT(*) = 1 THEN 'SINGLE' ELSE 'NORMAL' END
                FROM oa_attendance_punch
                WHERE company_id = ? AND user_id = ? AND punch_date = ?
                ON DUPLICATE KEY UPDATE
                    first_punch_time = VALUES(first_punch_time),
                    last_punch_time = VALUES(last_punch_time),
                    punch_count = VALUES(punch_count), status = VALUES(status), version = version + 1
                """, companyId, userId, date, companyId, userId, date);
    }

    private Map<String, Object> findSummary(long companyId, long userId, LocalDate date) {
        List<Map<String, Object>> rows = listSummaries(companyId, userId, date, date, null);
        return rows.isEmpty() ? Map.of("punchDate", date, "punchCount", 0, "status", "NO_RECORD") : rows.get(0);
    }

    private void ensureCompanyUser(ManagementUser user) {
        if (user == null || user.companyId() == null) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "平台账号不能进行个人打卡");
        }
    }

    private void ensureAdmin(ManagementUser user) {
        if (user == null || (!user.roles().contains("PLATFORM_ADMIN") && !user.roles().contains("COMPANY_ADMIN"))) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "仅管理员可以查看全部打卡记录");
        }
    }

    private String role(ManagementUser user) {
        return user.roles().contains("PLATFORM_ADMIN") ? "PLATFORM_ADMIN" : "COMPANY_ADMIN";
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("日期范围不正确");
        }
        if (to.toEpochDay() - from.toEpochDay() > 366) {
            throw new IllegalArgumentException("查询范围不能超过一年");
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private LocalDateTime parsePunchTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请输入打卡时间");
        }
        try {
            return LocalDateTime.parse(value.trim().replace(' ', 'T'));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("打卡时间格式不正确");
        }
    }

    private record Filter(Long orgUnitId, String keyword) {}
}
