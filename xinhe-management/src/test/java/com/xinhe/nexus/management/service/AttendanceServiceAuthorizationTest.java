package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

class AttendanceServiceAuthorizationTest {
    @Test
    void employeePunchRequiresLocation() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService service = new AttendanceService(db);
        ManagementUser employee = new ManagementUser(10L, 8L, "测试公司", "employee", "员工", List.of("EMPLOYEE"), List.of(), false, 1L);

        ManagementException exception = assertThrows(ManagementException.class,
                () -> service.punch(employee, "127.0.0.1", "test", null));

        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verifyNoInteractions(db);
    }

    @Test
    void allAttendanceRequiresExplicitPermissionForNonAdmin() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService service = new AttendanceService(db);
        ManagementUser employee = new ManagementUser(10L, 8L, "测试公司", "employee", "员工", List.of("EMPLOYEE"), List.of(), false, 1L);

        assertThrows(ManagementException.class,
                () -> service.listAll(employee, 8L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, null, null, 1, 20));
        verifyNoInteractions(db);
    }

    @Test
    void punchAndSummaryRefreshAreTransactional() throws NoSuchMethodException {
        assertTrue(AttendanceService.class.getMethod("punch", ManagementUser.class, String.class, String.class, AttendanceService.PunchInput.class).isAnnotationPresent(Transactional.class));
        assertTrue(AttendanceService.class.getDeclaredMethod("appendPunch", Long.class, long.class, Long.class, LocalDate.class, java.time.LocalDateTime.class, String.class, String.class, String.class, String.class, AttendanceService.PunchInput.class, Long.class).isAnnotationPresent(Transactional.class));
    }

    @Test
    void settlementStatusUsesHolidayPriorityAndPunchCountBoundaries() {
        org.junit.jupiter.api.Assertions.assertEquals("COMPANY_HOLIDAY", AttendanceService.resolveStatus(true, true, 2));
        org.junit.jupiter.api.Assertions.assertEquals("PERSONAL_HOLIDAY", AttendanceService.resolveStatus(false, true, 0));
        org.junit.jupiter.api.Assertions.assertEquals("ABSENT", AttendanceService.resolveStatus(false, false, 0));
        org.junit.jupiter.api.Assertions.assertEquals("SINGLE", AttendanceService.resolveStatus(false, false, 1));
        org.junit.jupiter.api.Assertions.assertEquals("NORMAL", AttendanceService.resolveStatus(false, false, 2));
        org.junit.jupiter.api.Assertions.assertEquals("NORMAL", AttendanceService.resolveStatus(false, false, 9));
    }

    @Test
    void webPunchImmediatelyWritesAndReturnsTheDailySummary() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService service = new AttendanceService(db);
        LocalDate date = LocalDate.of(2026, 11, 23);
        LocalDateTime time = LocalDateTime.of(2026, 11, 23, 9, 15);
        Map<String, Object> summary = Map.of(
                "punchDate", "2026-11-23", "punchCount", 1, "status", "RULE_MISSING");

        org.mockito.Mockito.when(db.queryForList(contains("SELECT id FROM sys_user_company"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 99L)));
        org.mockito.Mockito.when(db.queryForList(contains("FROM oa_attendance_rule_year"), any(Object[].class)))
                .thenReturn(List.of());
        org.mockito.Mockito.when(db.queryForMap(contains("MIN(punch_time)"), any(Object[].class)))
                .thenReturn(Map.of("firstPunchTime", time, "lastPunchTime", time, "punchCount", 1));
        org.mockito.Mockito.when(db.queryForList(contains("FROM oa_attendance_day_summary s"), any(Object[].class)))
                .thenReturn(List.of(summary));

        Map<String, Object> result = service.appendPunch(
                8L, 10L, null, date, time, "NORMAL", "WEB", "127.0.0.1", "test-agent",
                new AttendanceService.PunchInput(
                        java.math.BigDecimal.valueOf(30.2741), java.math.BigDecimal.valueOf(120.1551),
                        java.math.BigDecimal.valueOf(12), "BROWSER_GPS", null, null),
                null);

        org.junit.jupiter.api.Assertions.assertEquals(summary, result);
        InOrder order = inOrder(db);
        order.verify(db).queryForList(contains("SELECT id FROM sys_user_company"), any(Object[].class));
        order.verify(db).update(contains("INSERT INTO oa_attendance_punch"), any(Object[].class));
        order.verify(db).update(contains("INSERT INTO oa_attendance_day_summary"), any(Object[].class));
    }
}
