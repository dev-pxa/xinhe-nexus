package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class AttendanceDayEvaluationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 1, 8);
    private final AttendanceTimeRules.Range rule = new AttendanceTimeRules.Range(DAY, DAY, LocalTime.of(9,0), LocalTime.of(18,0), 30);

    private AttendanceDayEvaluation.Punch punch(String time, String type) {
        return new AttendanceDayEvaluation.Punch(DAY.atTime(LocalTime.parse(time)), type);
    }
    private AttendanceDayEvaluation result(List<AttendanceDayEvaluation.Punch> punches) {
        return AttendanceDayEvaluation.evaluate(DAY,DAY.plusDays(1),rule,punches);
    }

    @Test
    void inclusiveFlexibleBoundariesAreNormalAndOutsideBoundariesAreAbnormal() {
        assertEquals(new AttendanceDayEvaluation("NORMAL",false,false),
                result(List.of(punch("09:30","IN"),punch("17:30","OUT"))));
        assertEquals(new AttendanceDayEvaluation("LATE_EARLY",true,true),
                result(List.of(punch("09:30:01","IN"),punch("17:29:59","OUT"))));
        assertEquals("LATE", result(List.of(punch("09:31","IN"),punch("18:00","OUT"))).status());
        assertEquals("EARLY", result(List.of(punch("09:00","IN"),punch("17:00","OUT"))).status());
    }

    @Test
    void repeatedCheckinsDoNotBecomeCheckoutAndLatestCheckoutCanRepairEarlyDeparture() {
        assertEquals("MISSING_OUT", result(List.of(punch("09:00","IN"),punch("09:01","IN"))).status());
        assertEquals("NORMAL", result(List.of(punch("09:00","IN"),punch("17:00","OUT"),punch("18:00","OUT"))).status());
        assertEquals("NORMAL", result(List.of(punch("09:00","IN"),punch("10:00","IN"),punch("18:00","OUT"))).status());
    }

    @Test
    void missingPunchesWaitTodayAndBecomeExceptionsAfterTheDayEnds() {
        assertEquals("WAITING_OUT", AttendanceDayEvaluation.evaluate(DAY,DAY,rule,List.of(punch("09:00","IN"))).status());
        assertEquals("WAITING_IN", AttendanceDayEvaluation.evaluate(DAY,DAY,rule,List.of()).status());
        assertEquals("ABSENT",result(List.of()).status());
        assertEquals("MISSING_IN", result(List.of(punch("18:00","OUT"))).status());
        assertEquals("MISSING_OUT",result(List.of(punch("09:00","IN"),punch("08:00","OUT"))).status());
    }

    @Test
    void historicalUntypedPunchesAreClassifiedOnEitherSideOfTheShiftMidpoint() {
        assertEquals("MISSING_OUT",result(List.of(punch("09:00","NORMAL"),punch("09:01","NORMAL"))).status());
        assertEquals("NORMAL",result(List.of(punch("09:00","NORMAL"),punch("18:00","NORMAL"))).status());
    }

    @Test
    void zeroFlexibilityUsesTheScheduledTimeExactly() {
        var strict = new AttendanceTimeRules.Range(DAY,DAY,LocalTime.of(9,0),LocalTime.of(18,0),0);
        assertEquals("NORMAL",AttendanceDayEvaluation.evaluate(DAY,DAY,strict,
                List.of(punch("09:00","IN"),punch("18:00","OUT"))).status());
        assertEquals("LATE_EARLY",AttendanceDayEvaluation.evaluate(DAY,DAY,strict,
                List.of(punch("09:00:01","IN"),punch("17:59:59","OUT"))).status());
    }
}
