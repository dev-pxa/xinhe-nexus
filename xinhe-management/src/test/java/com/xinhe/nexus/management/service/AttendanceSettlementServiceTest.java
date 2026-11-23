package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AttendanceSettlementServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void refusesToSettleTodayOrFuture() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> service.settleDate(LocalDate.of(2026, 11, 22), false));
        assertThrows(IllegalArgumentException.class,
                () -> service.settleDate(LocalDate.of(2026, 11, 23), false));
        verifyNoInteractions(db, attendance);
    }

    @Test
    void settlesZeroPunchAndRepairTargetsAndMarksJobSuccessful() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);
        LocalDate date = LocalDate.of(2026, 11, 21);
        when(db.queryForList(contains("FROM sys_company"), eq(Long.class))).thenReturn(List.of(8L));
        when(db.queryForObject(contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(db.queryForObject(contains("GET_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(db.queryForList(contains("FROM sys_user_company"), eq(Long.class), any(Object[].class))).thenReturn(List.of(10L, 11L));
        when(db.queryForObject(contains("RELEASE_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(attendance.recalculateDay(eq(8L), any(Long.class), eq(date)))
                .thenReturn(Map.of("status", "NORMAL"));

        service.settleDate(date, false);

        verify(attendance).recalculateDay(8L, 10L, date);
        verify(attendance).recalculateDay(8L, 11L, date);
        verify(db).update(contains("status='SUCCESS'"), eq(2), eq(8L), eq(date));
        verify(db).queryForList(contains("COALESCE(p.punch_count,0)=0"), eq(Long.class), any(Object[].class));
    }

    @Test
    void skipsACompanyDateThatAlreadySettledSuccessfully() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);
        LocalDate date = LocalDate.of(2026, 11, 21);
        when(db.queryForList(contains("FROM sys_company"), eq(Long.class))).thenReturn(List.of(8L));
        when(db.queryForObject(contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class))).thenReturn(1);

        service.settleDate(date, false);

        verifyNoInteractions(attendance);
        verify(db, never()).queryForObject(contains("GET_LOCK"), eq(Integer.class), any(Object[].class));
    }

    @Test
    void skipsAlreadyConsistentPunchSummariesButStillCompletesTheJob() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);
        LocalDate date = LocalDate.of(2026, 11, 21);
        when(db.queryForList(contains("FROM sys_company"), eq(Long.class))).thenReturn(List.of(8L));
        when(db.queryForObject(contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(db.queryForObject(contains("GET_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(db.queryForList(contains("COALESCE(p.punch_count,0)=0"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of());
        when(db.queryForObject(contains("RELEASE_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);

        service.settleDate(date, false);

        verifyNoInteractions(attendance);
        verify(db).update(contains("status='SUCCESS'"), eq(0), eq(8L), eq(date));
    }

    @Test
    void missingEmployeeRuleMarksTheJobFailedWithoutCreatingFalseAbsence() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);
        LocalDate date = LocalDate.of(2026, 11, 21);
        when(db.queryForList(contains("FROM sys_company"), eq(Long.class))).thenReturn(List.of(8L));
        when(db.queryForObject(contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(db.queryForObject(contains("GET_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(db.queryForList(contains("FROM sys_user_company"), eq(Long.class), any(Object[].class))).thenReturn(List.of(10L));
        when(db.queryForObject(contains("RELEASE_LOCK"), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(attendance.recalculateDay(8L, 10L, date)).thenReturn(Map.of("status", "RULE_MISSING"));

        service.settleDate(date, false);

        verify(attendance).recalculateDay(8L, 10L, date);
        verify(db).update(contains("status='FAILED'"), eq(1), contains("未覆盖全部在职员工"), eq(8L), eq(date));
        verify(db).queryForObject(contains("RELEASE_LOCK"), eq(Integer.class), any(Object[].class));
    }

    @Test
    void scheduledSettlementContinuesFromLastSuccessBeyondBootstrapWindow() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AttendanceService attendance = mock(AttendanceService.class);
        AttendanceSettlementService service = new AttendanceSettlementService(db, attendance, CLOCK);
        when(db.queryForList(contains("FROM sys_company"), eq(Long.class))).thenReturn(List.of(8L));
        when(db.queryForObject(contains("MAX(settlement_date)"), eq(LocalDate.class), any(Object[].class)))
                .thenReturn(LocalDate.of(2026, 11, 10));
        when(db.queryForObject(contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        service.settleScheduled();

        verify(db, times(11)).queryForObject(
                contains("status='SUCCESS'"), eq(Integer.class), any(Object[].class));
        verifyNoInteractions(attendance);
    }
}
