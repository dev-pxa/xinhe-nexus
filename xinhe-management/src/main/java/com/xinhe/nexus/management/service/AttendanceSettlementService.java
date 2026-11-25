package com.xinhe.nexus.management.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AttendanceSettlementService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final AttendanceService attendance;
    private final Clock clock;
    private final TransactionRunner transactions;
    @Value("${xinhe.attendance.catchup-days:7}") private int catchupDays = 7;
    @Value("${xinhe.attendance.max-catchup-per-run:366}") private int maxCatchupPerRun = 366;

    @Autowired
    public AttendanceSettlementService(
            JdbcTemplate db, AttendanceService attendance, PlatformTransactionManager transactionManager) {
        this.db = db;
        this.attendance = attendance;
        this.clock = Clock.system(ZONE);
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions = action -> template.executeWithoutResult(status -> action.run());
    }

    AttendanceSettlementService(JdbcTemplate db, AttendanceService attendance, Clock clock) {
        this.db = db;
        this.attendance = attendance;
        this.clock = clock;
        this.transactions = Runnable::run;
    }

    @Scheduled(cron = "${xinhe.attendance.settlement-cron:0 0 1 * * *}", zone = "Asia/Shanghai")
    public void settleScheduled() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        for (Long companyId : enabledCompanies()) {
            LocalDate latestSuccess = db.queryForObject(
                    "SELECT MAX(settlement_date) FROM oa_attendance_settlement_job WHERE company_id=? AND status='SUCCESS'",
                    LocalDate.class, companyId);
            LocalDate earliestFailed = db.queryForObject(
                    "SELECT MIN(settlement_date) FROM oa_attendance_settlement_job WHERE company_id=? AND status='FAILED'",
                    LocalDate.class, companyId);
            LocalDate start = latestSuccess == null
                    ? yesterday.minusDays(Math.max(1, catchupDays) - 1L)
                    : latestSuccess.plusDays(1);
            if (earliestFailed != null && earliestFailed.isBefore(start)) start = earliestFailed;
            LocalDate end = start.plusDays(Math.max(1, maxCatchupPerRun) - 1L);
            if (end.isAfter(yesterday)) end = yesterday;
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                settleCompany(companyId, date, false);
            }
        }
    }

    public void settleDate(LocalDate date, boolean force) {
        if (date == null || !date.isBefore(LocalDate.now(clock))) {
            throw new IllegalArgumentException("只能结算今天以前的日期");
        }
        for (Long companyId : enabledCompanies()) settleCompany(companyId, date, force);
    }

    private List<Long> enabledCompanies() {
        return db.queryForList(
                "SELECT id FROM sys_company WHERE status='ENABLED' AND deleted_at IS NULL ORDER BY id",
                Long.class);
    }

    private void settleCompany(long companyId, LocalDate date, boolean force) {
        transactions.run(() -> settleCompanyInTransaction(companyId, date, force));
    }

    private void settleCompanyInTransaction(long companyId, LocalDate date, boolean force) {
        if (!force) {
            Integer done = db.queryForObject(
                    "SELECT COUNT(*) FROM oa_attendance_settlement_job WHERE company_id=? AND settlement_date=? AND status='SUCCESS'",
                    Integer.class, companyId, date);
            if (done != null && done > 0) return;
        }
        String lockName = "attendance:" + companyId + ":" + date;
        Integer locked = db.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, lockName);
        if (locked == null || locked != 1) return;
        int processed = 0;
        try {
            List<Long> users = db.queryForList("""
                SELECT uc.user_id
                FROM sys_user_company uc
                JOIN sys_user u ON u.id=uc.user_id
                LEFT JOIN (
                    SELECT user_id,COUNT(*) punch_count
                    FROM oa_attendance_punch
                    WHERE company_id=? AND punch_date=?
                    GROUP BY user_id
                ) p ON p.user_id=uc.user_id
                LEFT JOIN oa_attendance_day_summary s
                  ON s.company_id=uc.company_id AND s.user_id=uc.user_id AND s.punch_date=?
                WHERE uc.company_id=? AND DATE(uc.joined_at)<=? AND (uc.left_at IS NULL OR DATE(uc.left_at)>=?)
                  AND (uc.deleted_at IS NULL OR DATE(uc.deleted_at)>?) AND (u.deleted_at IS NULL OR DATE(u.deleted_at)>?)
                  AND (COALESCE(p.punch_count,0)=0 OR s.id IS NULL
                       OR s.punch_count<>p.punch_count OR s.status IN ('RULE_MISSING','ON_LEAVE','WAITING_IN','WAITING_OUT'))
                ORDER BY uc.user_id
                """, Long.class, companyId, date, date, companyId, date, date, date, date);
            db.update("""
                INSERT INTO oa_attendance_settlement_job(company_id,settlement_date,status,expected_count,processed_count,attempt_count,error_message,started_at,finished_at)
                VALUES(?,?,'RUNNING',?,0,1,NULL,NOW(),NULL)
                ON DUPLICATE KEY UPDATE status='RUNNING',expected_count=VALUES(expected_count),processed_count=0,attempt_count=attempt_count+1,error_message=NULL,started_at=NOW(),finished_at=NULL
                """, companyId, date, users.size());
            boolean ruleMissing = false;
            for (Long userId : users) {
                Map<String,Object> result = attendance.recalculateDay(companyId, userId, date);
                processed++;
                if ("RULE_MISSING".equals(result.get("status"))) ruleMissing = true;
            }
            if (ruleMissing) {
                throw new IllegalStateException(date.getYear() + "年度考勤规则未覆盖全部在职员工");
            }
            db.update("UPDATE oa_attendance_settlement_job SET status='SUCCESS',processed_count=?,error_message=NULL,finished_at=NOW() WHERE company_id=? AND settlement_date=?", processed, companyId, date);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            db.update("UPDATE oa_attendance_settlement_job SET status='FAILED',processed_count=?,error_message=?,finished_at=NOW() WHERE company_id=? AND settlement_date=?",
                    processed, message.substring(0, Math.min(1000, message.length())), companyId, date);
        } finally {
            db.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
        }
    }

    @FunctionalInterface
    private interface TransactionRunner {
        void run(Runnable action);
    }
}
