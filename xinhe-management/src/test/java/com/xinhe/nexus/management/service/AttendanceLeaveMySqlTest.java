package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.controller.ApiExceptionHandler;
import com.xinhe.nexus.management.controller.AttendanceLeaveController;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "XINHE_LEAVE_TEST_JDBC_URL", matches = ".+")
class AttendanceLeaveMySqlTest {
    private static final long COMPANY = 8400;
    private static final LocalDate START = LocalDate.of(2025, 6, 16);
    private static final ManagementUser EMPLOYEE = actor(8401, COMPANY, false);
    private static final ManagementUser MANAGER = actor(8402, COMPANY, false);
    private static final ManagementUser ADMIN = actor(8403, COMPANY, true);
    private static final ManagementUser OTHER_COMPANY = actor(8403, COMPANY + 1, true);
    private static JdbcTemplate db;
    private static TransactionTemplate transaction;
    private static DataSourceTransactionManager transactionManager;
    private static AttendanceLeaveService leave;
    private static AttendanceService attendance;

    @BeforeAll
    static void migrateAndSeed() {
        // Run against a dedicated empty MySQL database.
        var dataSource = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"), System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        db = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        attendance = new AttendanceService(db);
        leave = new AttendanceLeaveService(db, attendance);

        db.update("INSERT INTO sys_company(id,company_code,company_name) VALUES (?,'LEAVE_TEST','请假测试公司')", COMPANY);
        for (var user : List.of(EMPLOYEE, MANAGER, ADMIN, actor(8404, COMPANY, true))) {
            db.update("INSERT INTO sys_user(id,username,password_hash,display_name) VALUES (?,?,'test',?)",
                    user.id(), user.username(), user.name());
            db.update("""
                    INSERT INTO sys_user_company(user_id,company_id,is_company_admin,joined_at)
                    VALUES (?,?,?,'2025-01-01')
                    """, user.id(), COMPANY, user.roles().contains("COMPANY_ADMIN"));
        }
        db.update("""
                INSERT INTO sys_org_unit(id,company_id,org_code,org_name,org_path,org_level,manager_user_id)
                VALUES (8400,?,'LEAVE_TEST','测试部门','/8400/',1,?)
                """, COMPANY, MANAGER.id());
        db.update("""
                INSERT INTO sys_position(id,company_id,org_unit_id,position_code,position_name)
                VALUES (8400,?,8400,'LEAVE_TEST','测试岗位')
                """, COMPANY);
        db.update("INSERT INTO sys_user_position(company_id,user_id,position_id) VALUES (?,?,8400)",
                COMPANY, EMPLOYEE.id());
        seedRule(8400L, START.getYear());
        db.update("""
                INSERT INTO oa_attendance_company_holiday(rule_year_id,company_id,revision,holiday_date,created_by)
                VALUES (8400,?,1,?,8403)
                """, COMPANY, START.plusDays(3));
        db.update("""
                INSERT INTO oa_attendance_user_holiday(rule_year_id,company_id,revision,user_id,holiday_date,created_by)
                VALUES (8400,?,1,?,?,8403)
                """, COMPANY, EMPLOYEE.id(), START.plusDays(1));
    }

    @Test
    void routesTwoApprovalsAndPreservesBothKindsOfHoliday() throws Exception {
        var end = START.plusDays(4);
        var preview = leave.preview(EMPLOYEE, START, end);
        assertEquals(List.of("WORKDAY", "PERSONAL_HOLIDAY", "WORKDAY", "COMPANY_HOLIDAY", "WORKDAY"),
                preview.days().stream().map(day -> day.dayType().name()).toList());
        assertEquals(MANAGER.name(), preview.managerName());
        assertEquals(ADMIN.name(), preview.finalReviewerName());
        for (var day : preview.days()) {
            recalculate(day.date());
        }
        assertStatus(START, "ABSENT", null);

        var file = "%PDF-1.4\ntest".getBytes(StandardCharsets.US_ASCII);
        long id = transaction.execute(status -> leave.create(EMPLOYEE, input("SICK", START, end),
                new MockMultipartFile("attachment", "凭证.pdf", "application/pdf", file)));
        assertArrayEquals(file, leave.download(EMPLOYEE, id).bytes());
        assertEquals("application/pdf", leave.detail(EMPLOYEE, id).get("attachmentContentType"));
        var mvc = MockMvcBuilders.standaloneSetup(new AttendanceLeaveController(leave)).build();
        var response = mvc.perform(get("/api/oa/attendance/leave-requests/{id}/attachment", id)
                        .requestAttr(AuthConstants.CURRENT_USER_ATTRIBUTE, EMPLOYEE))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/pdf", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").startsWith("inline;"));
        assertArrayEquals(file, response.getContentAsByteArray());
        assertThrows(ManagementException.class, () -> leave.download(OTHER_COMPANY, id));
        assertThrows(ManagementException.class, () -> decide(ADMIN, id, true, ""));
        assertTrue(leave.list(ADMIN, true).stream().noneMatch(row -> ((Number) row.get("id")).longValue() == id));
        assertThrows(ManagementException.class, () -> create("OTHER", START, START));
        assertThrows(ManagementException.class, () -> create("PERSONAL", START.plusDays(1), START.plusDays(1)));
        assertStatus(START, "ABSENT", null);

        decide(MANAGER, id, true, "初审同意");
        assertEquals("PENDING_FINAL", leave.detail(EMPLOYEE, id).get("status"));
        assertEquals("初审同意", leave.detail(EMPLOYEE, id).get("managerComment"));
        assertTrue(leave.list(ADMIN, true).stream().anyMatch(row -> ((Number) row.get("id")).longValue() == id));
        assertThrows(ManagementException.class, () -> decide(MANAGER, id, true, ""));
        assertThrows(ManagementException.class, () -> decide(actor(8404, COMPANY, true), id, true, ""));
        assertStatus(START, "ABSENT", null);

        decide(ADMIN, id, true, "终审同意");
        assertEquals("APPROVED", leave.detail(EMPLOYEE, id).get("status"));
        assertEquals("终审同意", leave.detail(EMPLOYEE, id).get("finalComment"));
        assertEquals(3L, leave.detail(EMPLOYEE, id).get("leaveDays"));
        assertStatus(START, "ON_LEAVE", "SICK");
        assertStatus(START.plusDays(1), "PERSONAL_HOLIDAY", null);
        assertStatus(START.plusDays(2), "ON_LEAVE", "SICK");
        assertStatus(START.plusDays(3), "COMPANY_HOLIDAY", null);
        assertStatus(end, "ON_LEAVE", "SICK");

        transaction.executeWithoutResult(status -> attendance.adminPunch(ADMIN, COMPANY, EMPLOYEE.id(),
                START + " 09:00:00", null, null, null));
        assertStatus(START, "ON_LEAVE", "SICK");
        assertEquals(1, attendance.listMyPunches(EMPLOYEE, START).size());
        assertThrows(ManagementException.class,
                () -> transaction.executeWithoutResult(status -> leave.withdraw(EMPLOYEE, id)));
        assertThrows(ManagementException.class, () -> create("COMPENSATORY", START, START));
    }

    @Test
    void releasesRejectedAndWithdrawnDatesAndDefersFutureSummaries() {
        var day = START.plusDays(8);
        long rejected = create("OTHER", day, day);
        assertThrows(ManagementException.class, () -> decide(MANAGER, rejected, false, " "));
        decide(MANAGER, rejected, false, "请更正假种");
        assertEquals("REJECTED", leave.detail(EMPLOYEE, rejected).get("status"));
        assertEquals(1L, leave.detail(EMPLOYEE, rejected).get("leaveDays"));
        long withdrawn = create("PERSONAL", day, day);
        decide(MANAGER, withdrawn, true, "");
        transaction.executeWithoutResult(status -> leave.withdraw(EMPLOYEE, withdrawn));
        assertEquals("WITHDRAWN", leave.detail(EMPLOYEE, withdrawn).get("status"));
        assertTrue(leave.detail(EMPLOYEE, withdrawn).get("managerActedAt") != null);
        long replacement = create("COMPENSATORY", day, day);
        decide(MANAGER, replacement, true, "");
        decide(ADMIN, replacement, false, "调整调休日期");
        assertEquals("REJECTED", leave.detail(EMPLOYEE, replacement).get("status"));
        assertTrue(create("COMPENSATORY", day, day) > replacement);

        var future = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(10);
        seedRule(8401L, future.getYear());
        long futureId = create("COMPENSATORY", future, future);
        decide(MANAGER, futureId, true, "");
        decide(ADMIN, futureId, true, "");
        assertTrue(attendance.listMine(EMPLOYEE, future, future).isEmpty());
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var clock = Clock.fixed(future.plusDays(1).atStartOfDay(zone).toInstant(), zone);
        var settlement = new AttendanceSettlementService(db, attendance, clock);
        transaction.executeWithoutResult(status -> settlement.settleDate(future, false));
        assertStatus(future, "ON_LEAVE", "COMPENSATORY");
    }

    @Test
    void finalApprovalRequiresConfirmationWhenCalendarDatesChange() {
        var day = START.plusDays(15);
        long id = create("PERSONAL", day, day);
        decide(MANAGER, id, true, "");
        db.update("""
                INSERT INTO oa_attendance_company_holiday(rule_year_id,company_id,revision,holiday_date,created_by)
                VALUES (8400,?,1,?,8403)
                """, COMPANY, day);
        var error = assertThrows(ManagementException.class, () -> decide(ADMIN, id, true, ""));
        assertTrue(error.getMessage().contains("考勤规则已变化"));
        assertEquals("PENDING_FINAL", leave.detail(EMPLOYEE, id).get("status"));
        assertTrue(attendance.listMine(EMPLOYEE, day, day).isEmpty());
    }

    @Test
    void recalculationUsesApprovedLeaveEvenAfterAnOlderReadSnapshot() {
        var day = START.plusDays(18);
        long id = create("OTHER", day, day);
        decide(MANAGER, id, true, "");
        var snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var approve = new TransactionTemplate(transactionManager);
        approve.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        approve.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        snapshot.executeWithoutResult(status -> {
            assertEquals("PENDING_FINAL", leave.detail(EMPLOYEE, id).get("status"));
            approve.executeWithoutResult(inner -> leave.review(
                    ADMIN, id, new AttendanceLeaveService.Decision(true, "")));
            attendance.recalculateDay(COMPANY, EMPLOYEE.id(), day);
        });

        assertStatus(day, "ON_LEAVE", "OTHER");
    }

    @Test
    void acceptsIsoDatePreviewAndMultipartSubmissionThroughMvc() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AttendanceLeaveController(leave))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String url = "/api/oa/attendance/leave-requests";
        String date = START.plusDays(21).toString();
        mvc.perform(get(url + "/preview").requestAttr(AuthConstants.CURRENT_USER_ATTRIBUTE, EMPLOYEE)
                        .param("startDate", date).param("endDate", date))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].date").value(date))
                .andExpect(jsonPath("$.data.days[0].dayType").value("WORKDAY"));

        byte[] input = """
                {"leaveType":"PERSONAL","startDate":"%s","endDate":"%s","reason":"事假"}
                """.formatted(date, date).getBytes(StandardCharsets.UTF_8);
        transaction.executeWithoutResult(tx -> {
            try {
                mvc.perform(multipart(url)
                                .file(new MockMultipartFile("input", "blob", "application/json", input))
                                .requestAttr(AuthConstants.CURRENT_USER_ATTRIBUTE, EMPLOYEE))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").isNumber());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertTrue(leave.list(EMPLOYEE, false).stream()
                .anyMatch(row -> date.equals(row.get("startDate")) && "PENDING_MANAGER".equals(row.get("status"))));
    }

    @Test
    void departmentHeadIsApprovedDirectlyWhenTheNearestParentManagerIsACompanyAdmin() {
        db.update("""
                INSERT INTO sys_org_unit(id,company_id,parent_id,org_code,org_name,org_path,org_level,manager_user_id)
                VALUES (8401,?,8400,'PARENT_LEAVE','上级组织','/8400/8401/',2,?),
                       (8402,?,8401,'CHILD_LEAVE','申请人部门','/8400/8401/8402/',3,?)
                """, COMPANY, ADMIN.id(), COMPANY, MANAGER.id());
        db.update("""
                INSERT INTO sys_position(id,company_id,org_unit_id,position_code,position_name)
                VALUES (8401,?,8402,'LEAVE_HEAD','部门负责人')
                """, COMPANY);
        db.update("INSERT INTO sys_user_position(company_id,user_id,position_id) VALUES (?,?,8401)",
                COMPANY, MANAGER.id());
        var day = START.plusDays(24);
        var route = leave.preview(MANAGER, day, day);
        assertEquals(ADMIN.name(), route.managerName());
        assertNull(route.finalReviewerName());

        transaction.executeWithoutResult(status -> {
            db.update("UPDATE sys_user_company SET is_company_admin=0 WHERE company_id=? AND user_id=8404", COMPANY);
            assertNull(leave.preview(MANAGER, day, day).finalReviewerName());
            status.setRollbackOnly();
        });

        long id = transaction.execute(status -> leave.create(MANAGER, input("PERSONAL", day, day), null));
        assertEquals("申请人部门", leave.detail(MANAGER, id).get("departmentName"));
        assertNull(leave.detail(MANAGER, id).get("finalReviewerName"));
        assertThrows(ManagementException.class, () -> decide(actor(ADMIN.id(), COMPANY, false), id, true, ""));
        decide(ADMIN, id, true, "审批同意");
        var detail = leave.detail(MANAGER, id);
        assertEquals("APPROVED", detail.get("status"));
        assertEquals("审批同意", detail.get("managerComment"));
        assertNull(detail.get("finalActedAt"));
        var summary = attendance.listMine(MANAGER, day, day).get(0);
        assertEquals("ON_LEAVE", summary.get("status"));
        assertEquals("PERSONAL", summary.get("leaveType"));
    }

    private static void seedRule(long id, int year) {
        db.update("""
                INSERT INTO oa_attendance_rule_year(id,company_id,rule_year,status,company_configured,published_revision,created_by,updated_by)
                VALUES (?, ?, ?, 'PUBLISHED', 1, 1, 8403, 8403)
                """, id, COMPANY, year);
        db.update("""
                INSERT INTO oa_attendance_user_rule_config(rule_year_id,company_id,revision,user_id,baseline_date,implicit_holiday_credit,configured_by)
                SELECT ?, company_id, 1, user_id, CURRENT_DATE, 0, 8403
                FROM sys_user_company WHERE company_id=?
                """, id, COMPANY);
    }

    private void recalculate(LocalDate day) {
        transaction.executeWithoutResult(status -> attendance.recalculateDay(COMPANY, EMPLOYEE.id(), day));
    }

    private void assertStatus(LocalDate day, String status, String leaveType) {
        var result = attendance.listMine(EMPLOYEE, day, day).get(0);
        assertEquals(status, result.get("status"));
        assertEquals(leaveType, result.get("leaveType"));
    }

    private long create(String type, LocalDate start, LocalDate end) {
        return transaction.execute(status -> leave.create(EMPLOYEE, input(type, start, end), null));
    }

    private void decide(ManagementUser actor, long id, boolean approve, String comment) {
        transaction.executeWithoutResult(status -> leave.review(actor, id, new AttendanceLeaveService.Decision(approve, comment)));
    }

    private AttendanceLeaveService.Input input(String type, LocalDate start, LocalDate end) {
        return new AttendanceLeaveService.Input(type, start, end, "请假原因");
    }

    private static ManagementUser actor(long id, long company, boolean admin) {
        return new ManagementUser(id, company, "测试公司", "leave-test-" + id, "测试用户" + id,
                List.of(admin ? "COMPANY_ADMIN" : "COMPANY_USER"), List.of(), false, 1L);
    }
}
