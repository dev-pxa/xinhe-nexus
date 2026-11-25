package com.xinhe.nexus.management.service;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

record AttendanceCalendar(Long ruleId, Integer revision, boolean configured,
                          Set<LocalDate> companyHolidays, Set<LocalDate> personalHolidays) {
    enum DayType { WORKDAY, COMPANY_HOLIDAY, PERSONAL_HOLIDAY }

    static AttendanceCalendar load(JdbcTemplate db, long companyId, long userId, LocalDate from, LocalDate to) {
        var rules = db.queryForList("""
                SELECT r.id, r.published_revision AS revision, c.user_id AS configured_user
                FROM oa_attendance_rule_year r
                LEFT JOIN oa_attendance_user_rule_config c
                    ON c.rule_year_id=r.id AND c.revision=r.published_revision AND c.user_id=?
                WHERE r.company_id=? AND r.rule_year=? AND r.published_revision IS NOT NULL
                """, userId, companyId, from.getYear());
        if (rules.isEmpty()) return new AttendanceCalendar(null, null, false, Set.of(), Set.of());

        var rule = rules.get(0);
        long id = ((Number) rule.get("id")).longValue();
        int revision = ((Number) rule.get("revision")).intValue();
        if (rule.get("configured_user") == null) {
            return new AttendanceCalendar(id, revision, false, Set.of(), Set.of());
        }

        List<LocalDate> company = db.queryForList("""
                SELECT holiday_date FROM oa_attendance_company_holiday
                WHERE rule_year_id=? AND revision=? AND holiday_date BETWEEN ? AND ?
                """, LocalDate.class, id, revision, from, to);
        List<LocalDate> personal = db.queryForList("""
                SELECT holiday_date FROM oa_attendance_user_holiday
                WHERE rule_year_id=? AND revision=? AND user_id=? AND holiday_date BETWEEN ? AND ?
                """, LocalDate.class, id, revision, userId, from, to);
        return new AttendanceCalendar(id, revision, true, new HashSet<>(company), new HashSet<>(personal));
    }

    DayType dayType(LocalDate date) {
        if (companyHolidays.contains(date)) return DayType.COMPANY_HOLIDAY;
        if (personalHolidays.contains(date)) return DayType.PERSONAL_HOLIDAY;
        return DayType.WORKDAY;
    }
}
