package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

class AttendanceRuleServiceTest {
    private static final ManagementUser ADMIN = new ManagementUser(
            1L, 8L, "测试公司", "admin", "管理员",
            List.of("COMPANY_ADMIN"), List.of("*"), false, 1L);

    @Test
    void requiredPersonalHolidayCountUsesFloorOfDaysDividedBySeven() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        doReturn(List.of()).when(db).queryForList(anyString(), any(Object[].class));
        AttendanceRuleService service = new AttendanceRuleService(db);

        assertEquals(52, service.getYear(ADMIN, 2027).get("requiredPersonalHolidayCount"));
        assertEquals(52, service.getYear(ADMIN, 2028).get("requiredPersonalHolidayCount"));
    }

    @Test
    void savingPersonalHolidaysRejectsAnyCountOtherThanRequired() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        doReturn(List.of(Map.of("present", 1))).when(db)
                .queryForList(anyString(), any(Object[].class));
        when(db.queryForList(org.mockito.ArgumentMatchers.contains("FROM oa_attendance_user_rule_config"), any(Object[].class)))
                .thenReturn(List.of());
        AttendanceRuleService service = new AttendanceRuleService(db);
        List<String> dates = IntStream.range(0, 51)
                .mapToObj(offset -> LocalDate.of(2027, 1, 1).plusDays(offset).toString())
                .toList();

        ManagementException exception = assertThrows(ManagementException.class,
                () -> service.saveUser(ADMIN, 2027, 10L,
                        new AttendanceRuleService.DatesInput(dates, 0)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("剩余个人放假必须设置52天，当前已设置51天", exception.getMessage());
    }

    @Test
    void currentYearDeductsOneImplicitRestDayForEveryElapsedSevenDays() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        doReturn(List.of()).when(db).queryForList(anyString(), any(Object[].class));
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        AttendanceRuleService service = new AttendanceRuleService(db, clock);

        assertEquals(6, service.getYear(ADMIN, 2026).get("requiredPersonalHolidayCount"));
        assertEquals(52, service.getYear(ADMIN, 2027).get("requiredPersonalHolidayCount"));
        assertEquals(0, service.getYear(ADMIN, 2025).get("requiredPersonalHolidayCount"));
    }

    @Test
    void persistedBaselineOnlyDecreasesWhenConfiguredHolidayHasElapsed() {
        assertEquals(6, AttendanceRuleService.remainingPersonalHolidayCount(2026, 46, 0));
        assertEquals(5, AttendanceRuleService.remainingPersonalHolidayCount(2026, 46, 1));
        assertEquals(0, AttendanceRuleService.remainingPersonalHolidayCount(2026, 46, 6));
    }

    @Test
    void companyHolidayCannotAddOrRemoveTodayOrPastDates() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String,Object> head = Map.of("id", 9L, "companyConfigured", false, "draftRevision", 1,
                "publishedRevision", 0, "version", 0, "status", "DRAFT");
        when(db.queryForList(org.mockito.ArgumentMatchers.contains("FROM oa_attendance_rule_year"), any(Object[].class)))
                .thenReturn(List.of(head));
        when(db.queryForList(org.mockito.ArgumentMatchers.contains("SELECT holiday_date FROM oa_attendance_company_holiday"), any(Object[].class)))
                .thenReturn(List.of());
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        AttendanceRuleService service = new AttendanceRuleService(db, clock);

        ManagementException exception = assertThrows(ManagementException.class,
                () -> service.saveCompany(ADMIN, 2026,
                        new AttendanceRuleService.DatesInput(List.of("2026-11-22"), 0)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("2026-11-22及以前的考勤规则已冻结，不允许修改", exception.getMessage());
    }
}
