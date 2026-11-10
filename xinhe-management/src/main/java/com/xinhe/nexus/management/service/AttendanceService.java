package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceService {
    private static final ZoneId SYSTEM_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    public AttendanceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> punch(ManagementUser user, String ip, String userAgent, PunchInput input) {
        ensureCompanyUser(user);
        validateLocation(input, true);
        LocalDateTime now = LocalDateTime.now(SYSTEM_ZONE).withNano(0);
        LocalDate date = now.toLocalDate();
        return appendPunch(user.companyId(), user.id(), null, date, now, "NORMAL", "WEB", ip, userAgent, input, null);
    }

    public List<Map<String, Object>> listMine(ManagementUser user, LocalDate from, LocalDate to) {
        ensureCompanyUser(user);
        validateRange(from, to);
        return listSummaries(user.companyId(), user.id(), from, to, null, 10000, 0);
    }

    public List<Map<String, Object>> listMyPunches(ManagementUser user, LocalDate date) {
        ensureCompanyUser(user);
        if (date == null) throw new IllegalArgumentException("请选择日期");
        return jdbcTemplate.queryForList("SELECT id,punch_time punchTime,punch_type punchType,source,latitude,longitude,location_accuracy locationAccuracy,location_source locationSource,remark,created_at createdAt FROM oa_attendance_punch WHERE company_id=? AND user_id=? AND punch_date=? ORDER BY punch_time,id", user.companyId(), user.id(), date);
    }

    public List<Map<String, Object>> listPunches(ManagementUser user, Long companyId, long userId, LocalDate date) {
        requireAllView(user);
        if (date == null) throw new IllegalArgumentException("请选择日期");
        Long targetCompany = "PLATFORM_ADMIN".equals(role(user)) ? companyId : user.companyId();
        if (targetCompany == null) throw new IllegalArgumentException("请选择公司");
        if (!"PLATFORM_ADMIN".equals(role(user)) && !targetCompany.equals(user.companyId())) throw new ManagementException(HttpStatus.FORBIDDEN, "不能查看其他公司的打卡记录");
        return jdbcTemplate.queryForList("SELECT id,punch_time punchTime,punch_type punchType,source,latitude,longitude,location_accuracy locationAccuracy,location_source locationSource,remark,created_at createdAt FROM oa_attendance_punch WHERE company_id=? AND user_id=? AND punch_date=? ORDER BY punch_time,id", targetCompany, userId, date);
    }

    public Map<String, Object> listAll(
            ManagementUser user, Long companyId, LocalDate from, LocalDate to, Long userId, Long orgUnitId, String keyword, int page, int pageSize) {
        requireAllView(user);
        validateRange(from, to);
        page = Math.max(page, 1); pageSize = Math.min(Math.max(pageSize, 1), 100);
        Long scopeCompany = "PLATFORM_ADMIN".equals(role(user)) ? companyId : user.companyId();
        if (!"PLATFORM_ADMIN".equals(role(user)) && companyId != null && !companyId.equals(user.companyId())) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "不能查看其他公司的打卡记录");
        }
        Filter filter = new Filter(orgUnitId, keyword);
        List<Map<String,Object>> rows = listSummaries(scopeCompany, userId, from, to, filter, pageSize, (page - 1) * pageSize);
        long total = countSummaries(scopeCompany, userId, from, to, filter);
        return Map.of("data", rows, "total", total, "page", page, "pageSize", pageSize);
    }

    @Transactional
    public Map<String, Object> adminPunch(
            ManagementUser operator, Long companyId, long userId, String punchTime, String ip, String userAgent, AdminPunchInput input) {
        requirePermission(operator, "oa:attendance:admin-punch");
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
        validateLocation(input, false);
        PunchInput punchInput = input == null ? new PunchInput(null,null,null,"ADMIN_ENTRY",null,null) : new PunchInput(input.latitude(), input.longitude(), input.accuracy(), input.locationSource(), input.capturedAt(), input.remark());
        return appendPunch(targetCompany, userId, operator.id(), parsedTime.toLocalDate(), parsedTime, "NORMAL", "ADMIN", ip, userAgent, punchInput, null);
    }

    private List<Map<String, Object>> listSummaries(
            Long companyId, Long userId, LocalDate from, LocalDate to, Filter filter, int limit, int offset) {
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
        sql.append(" ORDER BY s.punch_date DESC, u.display_name, s.user_id LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
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

    @Transactional
    protected Map<String, Object> appendPunch(Long companyId, long userId, Long operatorUserId, LocalDate date,
            LocalDateTime punchTime, String punchType, String source, String ip, String userAgent,
            PunchInput input, Long correctionRequestId) {
        jdbcTemplate.update("""
                INSERT INTO oa_attendance_punch
                    (company_id, user_id, operator_user_id, correction_request_id, punch_date, punch_time, punch_type, source,
                     client_ip, user_agent, latitude, longitude, location_accuracy, location_source, client_captured_at, timezone, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, companyId, userId, operatorUserId, correctionRequestId, date, punchTime, punchType, source,
                ip, truncate(userAgent, 512), input == null ? null : input.latitude(), input == null ? null : input.longitude(),
                input == null ? null : input.accuracy(), input == null ? null : input.locationSource(),
                input == null ? null : parseCapturedAt(input.capturedAt()), SYSTEM_ZONE.getId(), input == null ? null : truncate(input.remark(), 255));
        refreshSummary(companyId, userId, date);
        return findSummary(companyId, userId, date);
    }

    private long countSummaries(Long companyId, Long userId, LocalDate from, LocalDate to, Filter filter) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT s.id) FROM oa_attendance_day_summary s JOIN sys_user u ON u.id=s.user_id AND u.deleted_at IS NULL LEFT JOIN sys_user_position up ON up.user_id=s.user_id AND up.company_id=s.company_id AND up.status='ENABLED' AND up.deleted_at IS NULL LEFT JOIN sys_position p ON p.id=up.position_id AND p.company_id=s.company_id AND p.status='ENABLED' AND p.deleted_at IS NULL LEFT JOIN sys_org_unit o ON o.id=p.org_unit_id AND o.company_id=s.company_id AND o.deleted_at IS NULL WHERE s.punch_date BETWEEN ? AND ?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(List.of(from, to));
        if(companyId!=null){sql.append(" AND s.company_id=?");args.add(companyId);} if(userId!=null){sql.append(" AND s.user_id=?");args.add(userId);}
        if(filter!=null&&filter.orgUnitId()!=null){sql.append(" AND o.id=?");args.add(filter.orgUnitId());}
        if(filter!=null&&filter.keyword()!=null&&!filter.keyword().isBlank()){sql.append(" AND (u.username LIKE ? OR u.display_name LIKE ?)");String key="%"+filter.keyword().trim()+"%";args.add(key);args.add(key);}
        Long count=jdbcTemplate.queryForObject(sql.toString(),Long.class,args.toArray()); return count==null?0:count;
    }

    private Map<String, Object> findSummary(long companyId, long userId, LocalDate date) {
        List<Map<String, Object>> rows = listSummaries(companyId, userId, date, date, null, 1, 0);
        return rows.isEmpty() ? Map.of("punchDate", date, "punchCount", 0, "status", "NO_RECORD") : rows.get(0);
    }

    public List<Map<String,Object>> createCorrection(ManagementUser user, CorrectionInput input) {
        ensureCompanyUser(user);
        if(input==null||input.requestedPunchTime()==null||input.requestedPunchTime().isBlank())throw bad("请输入补打卡时间");
        if(input.reason()==null||input.reason().isBlank())throw bad("请填写补打卡原因");
        LocalDateTime time=parsePunchTime(input.requestedPunchTime()); long approver=directManager(user);
        validateLocation(input, false);
        Integer duplicate=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM oa_attendance_correction_request WHERE company_id=? AND user_id=? AND attendance_date=? AND status='PENDING'",Integer.class,user.companyId(),user.id(),time.toLocalDate());
        if(duplicate!=null&&duplicate>0)throw bad("该日期已有待审批的补打卡申请");
        jdbcTemplate.update("INSERT INTO oa_attendance_correction_request(company_id,user_id,approver_user_id,attendance_date,punch_type,requested_punch_time,latitude,longitude,location_accuracy,location_source,location_address,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",user.companyId(),user.id(),approver,time.toLocalDate(),input.punchType()==null?"NORMAL":input.punchType(),time,input.latitude(),input.longitude(),input.accuracy(),input.locationSource(),trim(input.locationAddress()),input.reason().trim());
        return jdbcTemplate.queryForList("SELECT id,attendance_date attendanceDate,punch_type punchType,requested_punch_time requestedPunchTime,latitude,longitude,location_address locationAddress,status,reason FROM oa_attendance_correction_request WHERE company_id=? AND user_id=? ORDER BY id DESC",user.companyId(),user.id());
    }

    public List<Map<String,Object>> listMyCorrections(ManagementUser user){ensureCompanyUser(user);return jdbcTemplate.queryForList("SELECT id,attendance_date attendanceDate,punch_type punchType,requested_punch_time requestedPunchTime,latitude,longitude,location_address locationAddress,status,reason,review_comment reviewComment FROM oa_attendance_correction_request WHERE company_id=? AND user_id=? ORDER BY id DESC",user.companyId(),user.id());}
    public List<Map<String,Object>> listPendingCorrections(ManagementUser user){requirePermission(user,"oa:attendance:correction:review");ensureCompanyUser(user);return jdbcTemplate.queryForList("SELECT r.id,r.user_id userId,u.display_name userName,r.attendance_date attendanceDate,r.punch_type punchType,r.requested_punch_time requestedPunchTime,r.latitude,r.longitude,r.location_address locationAddress,r.reason,r.status FROM oa_attendance_correction_request r JOIN sys_user u ON u.id=r.user_id WHERE r.company_id=? AND r.approver_user_id=? AND r.status='PENDING' ORDER BY r.created_at",user.companyId(),user.id());}

    @Transactional public Map<String,Object> decideCorrection(ManagementUser user,long id,boolean approve,String comment){requirePermission(user,"oa:attendance:correction:review");ensureCompanyUser(user);Map<String,Object> r=jdbcTemplate.queryForMap("SELECT * FROM oa_attendance_correction_request WHERE company_id=? AND id=? AND approver_user_id=? FOR UPDATE",user.companyId(),id,user.id());if(!"PENDING".equals(r.get("status")))throw bad("申请不在待审批状态");if(!approve&& (comment==null||comment.isBlank()))throw bad("请填写驳回理由");String status=approve?"APPROVED":"REJECTED";jdbcTemplate.update("UPDATE oa_attendance_correction_request SET status=?,review_comment=?,reviewed_by=?,reviewed_at=NOW(),version=version+1 WHERE company_id=? AND id=? AND status='PENDING'",status,trim(comment),user.id(),user.companyId(),id);if(approve){long target=((Number)r.get("user_id")).longValue();LocalDate date=(r.get("attendance_date") instanceof java.sql.Date d)?d.toLocalDate():LocalDate.parse(r.get("attendance_date").toString());LocalDateTime time=toLocalDateTime(r.get("requested_punch_time"));PunchInput input=new PunchInput((BigDecimal)r.get("latitude"),(BigDecimal)r.get("longitude"),(BigDecimal)r.get("location_accuracy"), (String)r.get("location_source"),null,(String)r.get("reason"));appendPunch(user.companyId(),target,user.id(),date,time,(String)r.get("punch_type"),"CORRECTION",null,null,input,id);}return Map.of("id",id,"status",status);}

    private LocalDateTime toLocalDateTime(Object value){if(value instanceof java.sql.Timestamp timestamp)return timestamp.toLocalDateTime();if(value instanceof LocalDateTime dateTime)return dateTime;if(value instanceof java.util.Date date)return date.toInstant().atZone(SYSTEM_ZONE).toLocalDateTime();return LocalDateTime.parse(String.valueOf(value).replace(' ','T'));}

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

    private void requireAllView(ManagementUser user) {
        requirePermission(user, "oa:attendance:all:view");
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

    private LocalDateTime parseCapturedAt(String value) {
        if(value==null||value.isBlank())return null;
        try{return java.time.OffsetDateTime.parse(value.trim()).toLocalDateTime();}catch(RuntimeException ignored){}
        try{return LocalDateTime.parse(value.trim().replace(' ','T'));}catch(RuntimeException exception){return null;}
    }

    private void validateLocation(PunchInput input, boolean required) {
        if(input==null){if(required)throw bad("请允许定位后再打卡");return;}
        if(input.latitude()==null||input.longitude()==null){if(required)throw bad("请允许定位后再打卡");return;}
        if(input.latitude().compareTo(BigDecimal.valueOf(-90))<0||input.latitude().compareTo(BigDecimal.valueOf(90))>0||input.longitude().compareTo(BigDecimal.valueOf(-180))<0||input.longitude().compareTo(BigDecimal.valueOf(180))>0)throw bad("定位坐标无效");
        if(input.accuracy()!=null&&(input.accuracy().signum()<=0||input.accuracy().compareTo(BigDecimal.valueOf(10000))>0))throw bad("定位精度无效");
    }
    private void validateLocation(AdminPunchInput input, boolean required) { validateLocation(input==null?null:new PunchInput(input.latitude(),input.longitude(),input.accuracy(),input.locationSource(),input.capturedAt(),input.remark()),required); }
    private void validateLocation(CorrectionInput input, boolean required) { validateLocation(input==null?null:new PunchInput(input.latitude(),input.longitude(),input.accuracy(),input.locationSource(),null,input.reason()),required); }

    private long directManager(ManagementUser user){
        List<Long> orgs=jdbcTemplate.query("SELECT p.org_unit_id FROM sys_user_position up JOIN sys_position p ON p.id=up.position_id AND p.status='ENABLED' AND p.deleted_at IS NULL WHERE up.company_id=? AND up.user_id=? AND up.status='ENABLED' AND up.deleted_at IS NULL ORDER BY up.id",(rs,n)->rs.getLong(1),user.companyId(),user.id());
        for(Long orgId:orgs){String path=jdbcTemplate.queryForObject("SELECT org_path FROM sys_org_unit WHERE company_id=? AND id=? AND deleted_at IS NULL",String.class,user.companyId(),orgId);for(Map<String,Object> row:jdbcTemplate.queryForList("SELECT manager_user_id managerUserId,manager_position_id managerPositionId FROM sys_org_unit WHERE company_id=? AND status='ENABLED' AND deleted_at IS NULL AND ? LIKE CONCAT(org_path,'%') ORDER BY org_level DESC",user.companyId(),path)){Long manager=row.get("managerUserId")==null?null:((Number)row.get("managerUserId")).longValue();if(manager==null&&row.get("managerPositionId")!=null){List<Long> people=jdbcTemplate.query("SELECT user_id FROM sys_user_position WHERE company_id=? AND position_id=? AND status='ENABLED' AND deleted_at IS NULL ORDER BY id LIMIT 1",(rs,n)->rs.getLong(1),user.companyId(),row.get("managerPositionId"));if(!people.isEmpty())manager=people.get(0);}if(manager!=null&&manager!=user.id())return manager;}}
        throw bad("未找到直属负责人，请联系管理员配置组织负责人");
    }

    private ManagementException bad(String message){return new ManagementException(HttpStatus.BAD_REQUEST,message);}
    private String trim(String value){return value==null||value.isBlank()?null:value.trim();}
    private void requirePermission(ManagementUser user,String permission){if(user==null||user.companyId()==null)throw new ManagementException(HttpStatus.FORBIDDEN,"账号未关联公司");if(user.roles().contains("PLATFORM_ADMIN")||user.roles().contains("COMPANY_ADMIN")||user.permissions().contains("*")||user.permissions().contains(permission))return;throw new ManagementException(HttpStatus.FORBIDDEN,"当前岗位未配置操作权限："+permission);}
    public record PunchInput(BigDecimal latitude,BigDecimal longitude,BigDecimal accuracy,String locationSource,String capturedAt,String remark) {}
    public record AdminPunchInput(BigDecimal latitude,BigDecimal longitude,BigDecimal accuracy,String locationSource,String capturedAt,String remark) {}
    public record CorrectionInput(String requestedPunchTime,String punchType,BigDecimal latitude,BigDecimal longitude,BigDecimal accuracy,String locationSource,String locationAddress,String reason) {}
    private record Filter(Long orgUnitId, String keyword) {}
}
