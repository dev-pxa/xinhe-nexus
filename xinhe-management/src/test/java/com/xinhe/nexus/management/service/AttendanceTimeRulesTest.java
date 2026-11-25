package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttendanceTimeRulesTest {
    private AttendanceTimeRules.Range range(String from, String to, int start, int end) {
        return new AttendanceTimeRules.Range(LocalDate.parse(from), LocalDate.parse(to),
                LocalTime.of(start, 0), LocalTime.of(end, 0));
    }

    @Test
    void leapYearCoverageExcludesOnlyExemptDates() {
        var ranges = List.of(range("2028-01-01", "2028-02-28", 9, 18),
                range("2028-03-01", "2028-12-31", 8, 17));
        var missing = AttendanceTimeRules.missing(2028, AttendanceTimeRules.expand(2028, ranges), Set.of());
        assertEquals(List.of(LocalDate.of(2028, 2, 29)), missing);
        AttendanceTimeRules.requireCoverage(2028, ranges, Set.of(LocalDate.of(2028, 2, 29)));
    }

    @Test
    void rejectsOverlapInvalidDatesAndReversedTimes() {
        assertThrows(ManagementException.class, () -> AttendanceTimeRules.expand(2028,
                List.of(range("2028-01-01", "2028-06-01", 9, 18), range("2028-06-01", "2028-12-31", 9, 18))));
        assertThrows(ManagementException.class, () -> AttendanceTimeRules.expand(2028,
                List.of(range("2027-12-31", "2028-12-31", 9, 18))));
        assertThrows(ManagementException.class, () -> AttendanceTimeRules.expand(2028,
                List.of(range("2028-02-01", "2028-01-01", 9, 18))));
        assertThrows(ManagementException.class, () -> AttendanceTimeRules.expand(2028,
                List.of(range("2028-01-01", "2028-12-31", 18, 9))));
    }

    @Test
    void flexibilityDefaultsToThirtyAndValidatesBoundsAndFrozenDays() {
        var day = LocalDate.of(2028,1,1);
        var defaultRange = range("2028-01-01","2028-12-31",9,18);
        assertEquals(30,defaultRange.flexibleMinutes());
        var invalid = new AttendanceTimeRules.Range(day,day,LocalTime.of(9,0),LocalTime.of(18,0),-1);
        assertThrows(ManagementException.class,()->AttendanceTimeRules.expand(2028,List.of(invalid)));
        var changed = new AttendanceTimeRules.Range(day,LocalDate.of(2028,12,31),LocalTime.of(9,0),LocalTime.of(18,0),0);
        assertThrows(ManagementException.class,()->AttendanceTimeRules.freezePast(2028,List.of(defaultRange),List.of(changed),day,Set.of()));
    }

    @Test
    void frozenTimesMayBeResplitButCannotBeChanged() {
        var old = List.of(range("2028-01-01", "2028-12-31", 9, 18));
        var split = List.of(range("2028-01-01", "2028-03-01", 9, 18),
                range("2028-03-02", "2028-12-31", 8, 17));
        AttendanceTimeRules.freezePast(2028, old, split, LocalDate.of(2028, 3, 1), Set.of());
        assertThrows(ManagementException.class, () -> AttendanceTimeRules.freezePast(2028, old, split,
                LocalDate.of(2028, 3, 2), Set.of()));
        AttendanceTimeRules.freezePast(2028, List.of(), split, LocalDate.of(2028, 3, 2), Set.of());
    }
}
