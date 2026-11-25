package com.xinhe.nexus.management.service;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttendanceLeaveService {
    private static final Set<String> TYPES = Set.of("SICK", "COMPENSATORY", "PERSONAL", "OTHER");
    private static final ZoneId ATTENDANCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private static final String REQUEST_COLUMNS = """
            SELECT id, applicant_id AS applicantId, applicant_name AS applicantName,
                   department_name AS departmentName, leave_type AS leaveType,
                   DATE_FORMAT(start_date,'%Y-%m-%d') AS startDate,
                   DATE_FORMAT(end_date,'%Y-%m-%d') AS endDate,
                   status, reason, manager_id AS managerId, manager_name AS managerName,
                   manager_comment AS managerComment, manager_acted_at AS managerActedAt,
                   final_reviewer_id AS finalReviewerId, final_reviewer_name AS finalReviewerName,
                   final_comment AS finalComment, final_acted_at AS finalActedAt, submitted_at AS submittedAt,
                   (SELECT COUNT(*) FROM oa_attendance_leave_day d
                    WHERE d.request_id=oa_attendance_leave_request.id AND d.day_type='WORKDAY') AS leaveDays
            FROM oa_attendance_leave_request
            """;

    private final JdbcTemplate db;
    private final AttendanceService attendance;

    public AttendanceLeaveService(JdbcTemplate db, AttendanceService attendance) {
        this.db = db;
        this.attendance = attendance;
    }

    public Preview preview(ManagementUser actor, LocalDate start, LocalDate end) {
        requireCompany(actor);
        validateJoinedDate(start, applicantJoinedDate(actor.companyId(), actor.id(), false));
        return preview(actor, start, end, approvalRoute(actor));
    }

    private Preview preview(ManagementUser actor, LocalDate start, LocalDate end, Route route) {
        List<Day> days = calendarDays(actor.companyId(), actor.id(), start, end);
        List<LocalDate> conflicts = db.queryForList("""
                SELECT leave_date FROM oa_attendance_leave_day
                WHERE company_id=? AND applicant_id=? AND leave_date BETWEEN ? AND ? AND reserved=1
                ORDER BY leave_date
                """, LocalDate.class, actor.companyId(), actor.id(), start, end).stream()
                .filter(date -> days.contains(new Day(date, AttendanceCalendar.DayType.WORKDAY))).toList();
        return new Preview(days, conflicts, route.managerName(), route.finalReviewerName());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long create(ManagementUser actor, Input input, MultipartFile file) {
        requireCompany(actor);
        if (input == null || input.leaveType() == null || !TYPES.contains(input.leaveType())) {
            throw bad("请选择有效的假种");
        }
        String reason = clean(input.reason(), 1000, "请假原因");
        if (reason.isEmpty()) throw bad("请填写请假原因");
        Attachment attachment = readUpload(file);
        validateJoinedDate(input.startDate(), applicantJoinedDate(actor.companyId(), actor.id(), true));
        Route route = approvalRoute(actor);
        Preview preview = preview(actor, input.startDate(), input.endDate(), route);
        if (preview.days().stream().noneMatch(Day::workday)) throw bad("所选日期均为休息日，请选择应出勤日");
        if (!preview.conflictDates().isEmpty()) throw conflict("已有待审或已通过的请假日期 " + preview.conflictDates());
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO oa_attendance_leave_request(
                        company_id,applicant_id,applicant_name,department_name,leave_type,start_date,end_date,reason,
                        manager_id,manager_name,final_reviewer_id,final_reviewer_name)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            Object[] args = {actor.companyId(), actor.id(), actor.name(), route.departmentName(), input.leaveType(),
                    input.startDate(), input.endDate(), reason, route.managerId(), route.managerName(),
                    route.finalReviewerId(), route.finalReviewerName()};
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        db.batchUpdate("""
                INSERT INTO oa_attendance_leave_day(request_id,company_id,applicant_id,leave_date,day_type,reserved)
                VALUES(?,?,?,?,?,?)
                """, preview.days().stream()
                .map(day -> new Object[]{id, actor.companyId(), actor.id(), day.date(), day.dayType().name(),
                        day.workday() ? 1 : null}).toList());
        if (attachment != null) {
            db.update("""
                    INSERT INTO oa_attendance_leave_attachment(request_id,file_name,content_type,file_data)
                    VALUES(?,?,?,?)
                    """, id, attachment.fileName(), attachment.contentType(), attachment.bytes());
        }
        return id;
    }

    public List<Map<String, Object>> list(ManagementUser actor, boolean review) {
        requireCompany(actor);
        if (review) {
            return db.queryForList(REQUEST_COLUMNS + """
                    WHERE company_id=? AND
                         (manager_id=? OR (final_reviewer_id=? AND
                             (status='PENDING_FINAL' OR final_acted_at IS NOT NULL OR
                                 (status='WITHDRAWN' AND manager_acted_at IS NOT NULL))))
                    ORDER BY id DESC
                    """, actor.companyId(), actor.id(), actor.id());
        }
        return db.queryForList(REQUEST_COLUMNS + """
                WHERE company_id=? AND applicant_id=? ORDER BY id DESC
                """, actor.companyId(), actor.id());
    }

    public Map<String, Object> detail(ManagementUser actor, long id) {
        Map<String, Object> request = readable(actor, id);
        request.put("days", days(id));
        db.query("""
                SELECT file_name, content_type FROM oa_attendance_leave_attachment WHERE request_id=?
                """, rs -> {
            if (rs.next()) {
                request.put("attachmentName", rs.getString("file_name"));
                request.put("attachmentContentType", rs.getString("content_type"));
            }
            return null;
        }, id);
        request.put("canReview", canReview(actor, request));
        request.put("canWithdraw", number(request, "applicantId") == actor.id() && pending(request));
        return request;
    }

    public Attachment download(ManagementUser actor, long id) {
        readable(actor, id);
        Attachment attachment = db.query("""
                SELECT file_name,content_type,file_data FROM oa_attendance_leave_attachment WHERE request_id=?
                """, rs -> rs.next() ? new Attachment(rs.getString(1), rs.getString(2), rs.getBytes(3)) : null, id);
        if (attachment == null) throw missing();
        return attachment;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void withdraw(ManagementUser actor, long id) {
        Map<String, Object> request = readable(actor, id);
        if (number(request, "applicantId") != actor.id()) throw missing();
        applicantJoinedDate(actor.companyId(), actor.id(), true);
        if (db.update("""
                UPDATE oa_attendance_leave_request SET status='WITHDRAWN'
                WHERE id=? AND status IN ('PENDING_MANAGER','PENDING_FINAL')
                """, id) != 1) throw conflict("只有待审批的请假申请可以撤回");
        releaseDays(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void review(ManagementUser actor, long id, Decision decision) {
        Map<String, Object> request = readable(actor, id);
        long applicant = number(request, "applicantId");
        applicantJoinedDate(actor.companyId(), applicant, true);
        request = readable(actor, id);
        if (!canReview(actor, request)) throw conflict("当前申请已处理或未到您的审批环节");
        String comment = clean(decision.comment(), 1000, "审批意见");
        if (!decision.approve() && comment.isEmpty()) throw bad("请填写驳回原因");

        boolean managerStep = "PENDING_MANAGER".equals(request.get("status"));
        boolean finalStep = !managerStep || request.get("finalReviewerId") == null;
        String nextStatus = !decision.approve() ? "REJECTED" : finalStep ? "APPROVED" : "PENDING_FINAL";
        LocalDate end = LocalDate.parse((String) request.get("endDate"));
        List<Day> approvedDays = List.of();
        if ("APPROVED".equals(nextStatus)) {
            approvedDays = days(id);
            LocalDate start = LocalDate.parse((String) request.get("startDate"));
            if (!approvedDays.equals(calendarDays(actor.companyId(), applicant, start, end))) {
                throw conflict("请假日期的考勤规则已变化，请驳回并请申请人确认日期后重新提交");
            }
        }

        if (managerStep) {
            db.update("""
                    UPDATE oa_attendance_leave_request SET status=?, manager_comment=?, manager_acted_at=NOW()
                    WHERE id=?
                    """, nextStatus, comment, id);
        } else {
            db.update("""
                    UPDATE oa_attendance_leave_request SET status=?, final_comment=?, final_acted_at=NOW()
                    WHERE id=?
                    """, nextStatus, comment, id);
        }
        if (!decision.approve()) releaseDays(id);
        if ("APPROVED".equals(nextStatus)) recalculateApprovedDays(actor.companyId(), applicant, end, approvedDays);
    }

    private void recalculateApprovedDays(long companyId, long applicant, LocalDate end, List<Day> approvedDays) {
        LocalDate today = LocalDate.now(ATTENDANCE_ZONE);
        List<LocalDate> existingFutureSummaries = db.queryForList("""
                SELECT punch_date FROM oa_attendance_day_summary
                WHERE company_id=? AND user_id=? AND punch_date>? AND punch_date<=?
                """, LocalDate.class, companyId, applicant, today, end);
        for (Day day : approvedDays) {
            if (!day.date().isAfter(today) || existingFutureSummaries.contains(day.date())) {
                attendance.recalculateDay(companyId, applicant, day.date());
            }
        }
    }

    private Map<String, Object> readable(ManagementUser actor, long id) {
        requireCompany(actor);
        List<Map<String, Object>> rows = db.queryForList(REQUEST_COLUMNS + """
                WHERE company_id=? AND id=? AND
                    (applicant_id=? OR manager_id=? OR (final_reviewer_id=? AND
                        (status='PENDING_FINAL' OR final_acted_at IS NOT NULL OR
                            (status='WITHDRAWN' AND manager_acted_at IS NOT NULL))))
                """, actor.companyId(), id, actor.id(), actor.id(), actor.id());
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    private boolean canReview(ManagementUser actor, Map<String, Object> request) {
        if (number(request, "applicantId") == actor.id()) return false;
        return switch ((String) request.get("status")) {
            case "PENDING_MANAGER" -> number(request, "managerId") == actor.id()
                    && (request.get("finalReviewerId") != null || actor.roles().contains("COMPANY_ADMIN"));
            case "PENDING_FINAL" -> number(request, "finalReviewerId") == actor.id()
                    && actor.roles().contains("COMPANY_ADMIN");
            default -> false;
        };
    }

    private boolean pending(Map<String, Object> request) {
        return "PENDING_MANAGER".equals(request.get("status")) || "PENDING_FINAL".equals(request.get("status"));
    }

    private LocalDate applicantJoinedDate(long companyId, long applicantId, boolean lock) {
        LocalDate joined = db.query("""
                SELECT DATE(joined_at) FROM sys_user_company
                WHERE company_id=? AND user_id=? AND status='ENABLED' AND deleted_at IS NULL
                """ + (lock ? " FOR UPDATE" : ""),
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, companyId, applicantId);
        if (joined == null) throw conflict("申请人已不属于当前公司");
        return joined;
    }

    private void validateJoinedDate(LocalDate start, LocalDate joined) {
        if (start != null && start.isBefore(joined)) throw bad("请假日期不能早于入职日期");
    }

    private Route approvalRoute(ManagementUser actor) {
        var manager = OrganizationApprovers.nearest(db, actor.companyId(), actor.id(), null);
        if (manager == null) {
            throw bad("本部门及上级组织均未找到可审批您的负责人，请联系公司管理员配置");
        }
        long managerId = number(manager, "id");
        String departmentName = (String) manager.get("departmentName");
        String managerName = (String) manager.get("name");
        if (number(manager, "managerIsAdmin") == 1) {
            return new Route(departmentName, managerId, managerName, null, null);
        }
        List<Map<String, Object>> finals = db.queryForList("""
                SELECT u.id,u.display_name AS name FROM sys_user_company uc
                JOIN sys_user u ON u.id=uc.user_id AND u.status='ENABLED' AND u.deleted_at IS NULL
                WHERE uc.company_id=? AND uc.is_company_admin=1 AND uc.status='ENABLED'
                  AND uc.deleted_at IS NULL AND u.id NOT IN (?,?)
                ORDER BY uc.id LIMIT 1
                """, actor.companyId(), actor.id(), managerId);
        if (finals.isEmpty()) {
            throw bad("已匹配初审人 " + manager.get("name")
                    + "，请配置另一位公司顶层管理员负责终审");
        }
        var reviewer = finals.get(0);
        return new Route(departmentName, managerId, managerName,
                number(reviewer, "id"), (String) reviewer.get("name"));
    }

    private List<Day> calendarDays(long companyId, long userId, LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 365
                || start.getYear() < 2000 || end.getYear() > 9999) {
            throw bad("请选择有效的请假日期，单次申请最多覆盖366天");
        }
        List<Day> days = new ArrayList<>();
        LocalDate first = start;
        while (!first.isAfter(end)) {
            LocalDate yearEnd = first.withDayOfYear(first.lengthOfYear());
            LocalDate last = end.isBefore(yearEnd) ? end : yearEnd;
            AttendanceCalendar calendar = AttendanceCalendar.load(db, companyId, userId, first, last);
            if (!calendar.configured()) throw bad(first.getYear() + "年考勤规则未覆盖申请员工");
            for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                days.add(new Day(day, calendar.dayType(day)));
            }
            first = last.plusDays(1);
        }
        return days;
    }

    private List<Day> days(long id) {
        return db.query("""
                SELECT leave_date,day_type FROM oa_attendance_leave_day WHERE request_id=? ORDER BY leave_date
                """, (rs, row) -> new Day(rs.getObject(1, LocalDate.class),
                AttendanceCalendar.DayType.valueOf(rs.getString(2))), id);
    }

    private void releaseDays(long id) {
        db.update("UPDATE oa_attendance_leave_day SET reserved=NULL WHERE request_id=? AND reserved=1", id);
    }

    private Attachment readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() > MAX_ATTACHMENT_BYTES) throw bad("证明附件不能超过5MB");
        try {
            byte[] bytes = file.getBytes();
            String type;
            if (startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                type = "application/pdf";
            } else if (startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
                type = "image/jpeg";
            } else if (startsWith(bytes, new byte[]{(byte) 0x89, 80, 78, 71, 13, 10, 26, 10})) {
                type = "image/png";
            } else {
                throw bad("证明附件仅支持 PDF、JPG 或 PNG");
            }
            String name = file.getOriginalFilename();
            if (name == null) name = "证明附件";
            name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
            return new Attachment(name.substring(0, Math.min(name.length(), 200)), type, bytes);
        } catch (IOException exception) {
            throw bad("无法读取证明附件，请重新选择文件");
        }
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length
                && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private String clean(String text, int max, String label) {
        String value = text == null ? "" : text.strip();
        if (value.length() > max) throw bad(label + "不能超过" + max + "字");
        return value;
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void requireCompany(ManagementUser actor) {
        if (actor.companyId() == null || actor.roles().contains("PLATFORM_ADMIN")) {
            throw new ManagementException(HttpStatus.FORBIDDEN, "仅公司员工可以使用请假功能");
        }
    }

    private ManagementException bad(String message) {
        return new ManagementException(HttpStatus.BAD_REQUEST, message);
    }

    private ManagementException conflict(String message) {
        return new ManagementException(HttpStatus.CONFLICT, message);
    }

    private ManagementException missing() {
        return new ManagementException(HttpStatus.NOT_FOUND, "请假申请或附件不存在");
    }

    public record Input(String leaveType, LocalDate startDate, LocalDate endDate, String reason) {}
    public record Decision(boolean approve, String comment) {}
    public record Preview(List<Day> days, List<LocalDate> conflictDates, String managerName, String finalReviewerName) {}
    public record Day(LocalDate date, AttendanceCalendar.DayType dayType) {
        boolean workday() { return dayType == AttendanceCalendar.DayType.WORKDAY; }
    }
    public record Attachment(String fileName, String contentType, byte[] bytes) {}
    private record Route(String departmentName, long managerId, String managerName,
                         Long finalReviewerId, String finalReviewerName) {}
}
