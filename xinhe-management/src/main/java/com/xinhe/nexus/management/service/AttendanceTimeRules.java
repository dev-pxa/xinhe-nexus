package com.xinhe.nexus.management.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** Date ranges define company working times. Holidays remain date-only calendar entries. */
final class AttendanceTimeRules {
    private AttendanceTimeRules() {}

    static List<Range> load(JdbcTemplate db, long ruleId, int revision) {
        return db.query("""
                SELECT start_date,end_date,start_time,end_time,flexible_minutes FROM oa_attendance_time_range
                WHERE rule_year_id=? AND revision=? ORDER BY start_date,id
                """, (rs, row) -> new Range(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class),
                rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class), rs.getInt(5)), ruleId, revision);
    }

    static Set<LocalDate> exemptDates(JdbcTemplate db, long companyId, long ruleId, int revision) {
        Set<LocalDate> result = new LinkedHashSet<>(db.queryForList("""
                SELECT holiday_date FROM oa_attendance_company_holiday WHERE rule_year_id=? AND revision=?
                """, LocalDate.class, ruleId, revision));
        // Personal holidays exempt a company date only when every active employee is resting.
        result.addAll(db.queryForList("""
                SELECT h.holiday_date FROM oa_attendance_user_holiday h
                JOIN sys_user_company uc ON uc.company_id=h.company_id AND uc.user_id=h.user_id
                    AND uc.status='ENABLED' AND uc.deleted_at IS NULL
                JOIN sys_user u ON u.id=uc.user_id AND u.status='ENABLED' AND u.deleted_at IS NULL
                WHERE h.rule_year_id=? AND h.revision=?
                GROUP BY h.holiday_date
                HAVING COUNT(DISTINCT h.user_id) = (
                    SELECT COUNT(*) FROM sys_user_company m
                    JOIN sys_user s ON s.id=m.user_id AND s.status='ENABLED' AND s.deleted_at IS NULL
                    WHERE m.company_id=? AND m.status='ENABLED' AND m.deleted_at IS NULL)
                """, LocalDate.class, ruleId, revision, companyId));
        return result;
    }

    static Map<LocalDate, Range> expand(int year, List<Range> ranges) {
        if (ranges == null || ranges.size() > 366) throw bad("请填写考勤时间范围，最多366段");
        Map<LocalDate, Range> days = new LinkedHashMap<>();
        for (Range range : ranges) {
            if (range == null || range.startDate() == null || range.endDate() == null
                    || range.startTime() == null || range.endTime() == null) throw bad("日期及上下班时间不能为空");
            if (range.startDate().getYear() != year || range.endDate().getYear() != year) {
                throw bad("日期范围必须在" + year + "年内");
            }
            if (range.endDate().isBefore(range.startDate())) throw bad("结束日期不能早于开始日期");
            if (!range.startTime().isBefore(range.endTime())) throw bad("下班时间必须晚于上班时间");
            if (range.startTime().getSecond() != 0 || range.endTime().getSecond() != 0
                    || range.startTime().getNano() != 0 || range.endTime().getNano() != 0) throw bad("考勤时间精确到分钟");
            if (range.flexibleMinutes() < 0
                    || range.flexibleMinutes() > 1439) {
                throw bad("弹性分钟数应为0至1439之间的整数");
            }
            for (LocalDate day = range.startDate(); !day.isAfter(range.endDate()); day = day.plusDays(1)) {
                if (days.putIfAbsent(day, range) != null) throw bad("考勤时间范围重叠：" + day);
            }
        }
        return days;
    }

    static List<LocalDate> missing(int year, Map<LocalDate, Range> days, Set<LocalDate> exemptions) {
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate day = LocalDate.of(year, 1, 1); day.getYear() == year; day = day.plusDays(1)) {
            if (!days.containsKey(day) && !exemptions.contains(day)) missing.add(day);
        }
        return missing;
    }

    static void requireCoverage(int year, List<Range> ranges, Set<LocalDate> exemptions) {
        List<LocalDate> missing = missing(year, expand(year, ranges), exemptions);
        if (!missing.isEmpty()) throw bad("考勤时间未覆盖全年应出勤日期，共缺少" + missing.size()
                + "天：" + missing.stream().limit(8).map(LocalDate::toString).reduce((a, b) -> a + "、" + b).orElse(""));
    }

    static void freezePast(int year, List<Range> oldRanges, List<Range> ranges,
                           LocalDate today, Set<LocalDate> exemptions) {
        var previous = expand(year, oldRanges);
        var next = expand(year, ranges);
        for (var entry : previous.entrySet()) {
            LocalDate day = entry.getKey();
            if (day.isAfter(today) || exemptions.contains(day)) continue;
            Range after = next.get(day);
            Range before = entry.getValue();
            if (after == null || !before.startTime().equals(after.startTime()) || !before.endTime().equals(after.endTime())
                    || !before.flexibleMinutes().equals(after.flexibleMinutes())) {
                throw bad("今天及过去已生效的考勤时间不可修改：" + day);
            }
        }
    }

    private static ManagementException bad(String message) {
        return new ManagementException(HttpStatus.BAD_REQUEST, message);
    }

    public record Range(LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime, Integer flexibleMinutes) {
        public Range {
            if (flexibleMinutes == null) flexibleMinutes = 30;
        }
        public Range(LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime) {
            this(startDate, endDate, startTime, endTime, 30);
        }
    }
}
