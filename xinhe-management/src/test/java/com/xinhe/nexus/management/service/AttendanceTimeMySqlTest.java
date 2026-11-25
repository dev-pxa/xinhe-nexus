package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.*;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.controller.AttendanceRuleController;
import java.time.*;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="XINHE_LEAVE_TEST_JDBC_URL", matches=".+")
class AttendanceTimeMySqlTest {
    @Test
    void savesFullWorkingYearPublishesAndRespectsEachEmployeesHolidays() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"), System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var service = new AttendanceRuleService(db, Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneId.of("Asia/Shanghai")));
        var admin = new ManagementUser(9901, 9900L, "时间测试", "time-admin", "管理员",
                List.of("COMPANY_ADMIN"), List.of(), false, 1L);
        var employee = new ManagementUser(9902, 9900L, "时间测试", "time-user", "员工",
                List.of("COMPANY_USER"), List.of(), false, 1L);
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO sys_company(id,company_code,company_name) VALUES (9900,'TIME_TEST','时间测试')");
            db.update("""
                    INSERT INTO sys_user(id,username,password_hash,display_name) VALUES
                    (9901,'time-admin','test','管理员'),(9902,'time-user','test','员工')
                    """);
            db.update("INSERT INTO sys_user_company(company_id,user_id,is_company_admin) VALUES (9900,9901,1),(9900,9902,0)");
            db.update("""
                    INSERT INTO oa_attendance_rule_year(id,company_id,rule_year,company_configured,created_by,updated_by)
                    VALUES (9900,9900,2028,1,9901,9901)
                    """);
            for (long userId : List.of(9901L, 9902L)) {
                db.update("""
                        INSERT INTO oa_attendance_user_rule_config(rule_year_id,company_id,revision,user_id,
                            implicit_holiday_credit,baseline_date,configured_by)
                        VALUES (9900,9900,1,?,0,'2028-01-01',9901)
                        """, userId);
                for (int offset=0; offset<52; offset++) {
                    db.update("""
                            INSERT INTO oa_attendance_user_holiday(rule_year_id,company_id,revision,user_id,holiday_date,created_by)
                            VALUES (9900,9900,1,?,?,9901)
                            """, userId, LocalDate.of(2028,1,2).plusDays(offset));
                }
            }
            db.update("""
                    INSERT INTO oa_attendance_company_holiday(rule_year_id,company_id,revision,holiday_date,created_by)
                    VALUES (9900,9900,1,'2028-01-01',9901)
                    """);
        });
        var ranges = List.of(range("2028-02-23", "2028-06-30",9,18), range("2028-07-01","2028-12-31",8,17));
        var saved = tx.execute(status -> service.saveTimes(admin,2028,new AttendanceRuleService.TimeInput(ranges)));
        assertEquals(2, ((List<?>)saved.get("timeRanges")).size());
        assertEquals("UNCONFIGURED", service.mySchedule(employee,LocalDate.of(2028,3,1)).get("status"));
        tx.executeWithoutResult(status -> service.publish(admin,2028,new AttendanceRuleService.VersionInput(null)));
        assertEquals("09:00", service.mySchedule(employee,LocalDate.of(2028,3,1)).get("startTime"));
        assertEquals("08:00", service.mySchedule(employee,LocalDate.of(2028,7,1)).get("startTime"));
        assertEquals("COMPANY_HOLIDAY",service.mySchedule(employee,LocalDate.of(2028,1,1)).get("status"));
        assertFalse(service.mySchedule(employee,LocalDate.of(2028,1,1)).containsKey("startTime"));
        assertEquals("PERSONAL_HOLIDAY",service.mySchedule(employee,LocalDate.of(2028,1,2)).get("status"));
        assertThrows(ManagementException.class, () -> tx.executeWithoutResult(status ->
                service.saveTimes(employee,2028,new AttendanceRuleService.TimeInput(ranges))));
        var missingLeapDay = List.of(range("2028-02-23","2028-02-28",9,18),range("2028-03-01","2028-12-31",9,18));
        assertThrows(ManagementException.class, () -> tx.executeWithoutResult(status ->
                service.saveTimes(admin,2028,new AttendanceRuleService.TimeInput(missingLeapDay))));
        assertEquals(1, db.queryForObject("SELECT published_revision FROM oa_attendance_rule_year WHERE id=9900", Integer.class));

        var changed = List.of(range("2028-02-23","2028-12-31",10,19));
        tx.executeWithoutResult(status -> service.saveTimes(admin,2028,new AttendanceRuleService.TimeInput(changed)));
        assertEquals("09:00", service.mySchedule(employee,LocalDate.of(2028,3,1)).get("startTime"));
        assertEquals(2, db.queryForObject("SELECT draft_revision FROM oa_attendance_rule_year WHERE id=9900", Integer.class));
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM oa_attendance_user_holiday WHERE rule_year_id=9900 AND revision=2 AND user_id=9901 AND holiday_date='2028-01-02'");
            var exempt = AttendanceTimeRules.exemptDates(db,9900,9900,2);
            assertFalse(exempt.contains(LocalDate.of(2028,1,2)));
            assertThrows(ManagementException.class, () -> AttendanceTimeRules.requireCoverage(2028,changed,exempt));
            status.setRollbackOnly();
        });
        var mvc = MockMvcBuilders.standaloneSetup(new AttendanceRuleController(service)).build();
        tx.executeWithoutResult(status -> {
            try {
                mvc.perform(put("/api/oa/attendance/rules/2028/time-ranges")
                        .requestAttr(AuthConstants.CURRENT_USER_ATTRIBUTE, admin)
                        .contentType("application/json")
                        .content("{\"ranges\":[{\"startDate\":\"2028-02-23\",\"endDate\":\"2028-12-31\",\"startTime\":\"10:00\",\"endTime\":\"19:00\"}]}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeRanges[0].startTime").value("10:00:00"));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }
    private AttendanceTimeRules.Range range(String from, String to, int start, int end) {
        return new AttendanceTimeRules.Range(LocalDate.parse(from),LocalDate.parse(to),LocalTime.of(start,0),LocalTime.of(end,0));
    }
}
