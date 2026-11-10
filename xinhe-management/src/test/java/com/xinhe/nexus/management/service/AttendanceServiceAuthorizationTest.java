package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
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
}
