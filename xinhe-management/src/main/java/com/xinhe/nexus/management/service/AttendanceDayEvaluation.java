package com.xinhe.nexus.management.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Evaluates typed punches and reads older untyped punches without rewriting original records. */
record AttendanceDayEvaluation(String status, boolean late, boolean early) {
    record Punch(LocalDateTime time, String type) {}

    static AttendanceDayEvaluation evaluate(LocalDate date, LocalDate today,
                                             AttendanceTimeRules.Range rule, List<Punch> punches) {
        LocalDateTime midpoint = date.atTime(rule.startTime()).plusSeconds(
                java.time.Duration.between(rule.startTime(), rule.endTime()).getSeconds() / 2);
        LocalDateTime checkIn = null;
        LocalDateTime checkOut = null;
        for (Punch punch : punches) {
            String type = punch.type();
            if ("NORMAL".equals(type)) type = punch.time().isBefore(midpoint) ? "IN" : "OUT";
            if ("IN".equals(type) && (checkIn == null || punch.time().isBefore(checkIn))) checkIn = punch.time();
            if ("OUT".equals(type) && (checkOut == null || punch.time().isAfter(checkOut))) checkOut = punch.time();
        }
        // A checkout before the check-in cannot complete the working day.
        if (checkIn != null && checkOut != null && checkOut.isBefore(checkIn)) checkOut = null;
        boolean late = checkIn != null && checkIn.isAfter(date.atTime(rule.startTime()).plusMinutes(rule.flexibleMinutes()));
        boolean early = checkOut != null && checkOut.isBefore(date.atTime(rule.endTime()).minusMinutes(rule.flexibleMinutes()));
        boolean complete = date.isBefore(today);
        String status;
        if (punches.isEmpty()) status = complete ? "ABSENT" : "WAITING_IN";
        else if (checkIn == null) status = complete ? "MISSING_IN" : "WAITING_IN";
        else if (checkOut == null) status = complete ? "MISSING_OUT" : "WAITING_OUT";
        else if (late && early) status = "LATE_EARLY";
        else if (late) status = "LATE";
        else if (early) status = "EARLY";
        else status = "NORMAL";
        return new AttendanceDayEvaluation(status, late, early);
    }
}
