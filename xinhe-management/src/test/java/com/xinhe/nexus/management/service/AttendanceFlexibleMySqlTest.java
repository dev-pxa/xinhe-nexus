package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.xinhe.nexus.management.auth.ManagementUser;
import java.time.*;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="XINHE_LEAVE_TEST_JDBC_URL",matches=".+")
class AttendanceFlexibleMySqlTest {
    @Test
    void evaluatesTypedPunchesAndRecalculatesAfterCorrectionAndLeaveApproval() {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"),System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var attendance = new AttendanceService(db);
        var today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var day = today.minusDays(1);
        var employee = new ManagementUser(9922,9920L,"弹性测试","flex-user","员工",List.of("COMPANY_USER"),List.of(),false,1L);
        var admin = new ManagementUser(9921,9920L,"弹性测试","flex-admin","管理员",List.of("COMPANY_ADMIN"),List.of(),false,1L);
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO sys_company(id,company_code,company_name) VALUES (9920,'FLEX_TEST','弹性测试')");
            db.update("INSERT INTO sys_user(id,username,password_hash,display_name) VALUES (9921,'flex-admin','test','管理员'),(9922,'flex-user','test','员工')");
            db.update("INSERT INTO sys_user_company(company_id,user_id,is_company_admin,joined_at) VALUES (9920,9921,1,'2025-01-01'),(9920,9922,0,'2025-01-01')");
            db.update("""
                    INSERT INTO sys_org_unit(id,company_id,org_code,org_name,org_path,org_level,manager_user_id)
                    VALUES (9920,9920,'FLEX','部门','/9920/',1,9921)
                    """);
            db.update("INSERT INTO sys_position(id,company_id,org_unit_id,position_code,position_name) VALUES (9920,9920,9920,'FLEX','员工')");
            db.update("INSERT INTO sys_user_position(company_id,user_id,position_id) VALUES (9920,9922,9920)");
            db.update("""
                    INSERT INTO oa_attendance_rule_year(id,company_id,rule_year,company_configured,published_revision,status,created_by,updated_by)
                    VALUES (9920,9920,?,1,1,'PUBLISHED',9921,9921)
                    """,day.getYear());
            db.update("""
                    INSERT INTO oa_attendance_user_rule_config(rule_year_id,company_id,revision,user_id,baseline_date,implicit_holiday_credit,configured_by)
                    VALUES (9920,9920,1,9922,'2025-01-01',0,9921)
                    """);
            db.update("""
                    INSERT INTO oa_attendance_time_range(rule_year_id,revision,start_date,end_date,start_time,end_time)
                    VALUES(9920,1,?,?,'09:00','18:00')
                    """,LocalDate.of(day.getYear(),1,1),LocalDate.of(day.getYear(),12,31));
            assertEquals(30,AttendanceTimeRules.load(db,9920,1).get(0).flexibleMinutes());

            attendance.appendPunch(9920L,9922,null,day,day.atTime(9,31),"IN","WEB",null,null,null,null);
            attendance.appendPunch(9920L,9922,null,day,day.atTime(10,0),"IN","WEB",null,null,null,null);
            assertEquals("MISSING_OUT",attendance.listMine(employee,day,day).get(0).get("status"));
            attendance.appendPunch(9920L,9922,null,day,day.atTime(17,29),"OUT","WEB",null,null,null,null);
            var summary = attendance.listMine(employee,day,day).get(0);
            assertEquals("LATE_EARLY",summary.get("status"));
            assertEquals(1,((Number)summary.get("late")).intValue());
            assertEquals(1,((Number)summary.get("early")).intValue());

            var requests = attendance.createCorrection(employee,new AttendanceService.CorrectionInput(
                    day+" 17:30:00","OUT",null,null,null,null,null,"补下班打卡"));
            attendance.decideCorrection(admin,((Number)requests.get(0).get("id")).longValue(),true,"同意");
            summary = attendance.listMine(employee,day,day).get(0);
            assertEquals("LATE",summary.get("status"));
            assertEquals(0,((Number)summary.get("early")).intValue());
            assertEquals(4,attendance.listMyPunches(employee,day).size());

            var leave = new AttendanceLeaveService(db,attendance);
            long requestId = leave.create(employee,new AttendanceLeaveService.Input("SICK",day,day,"病假"),null);
            leave.review(admin,requestId,new AttendanceLeaveService.Decision(true,"同意"));
            summary = attendance.listMine(employee,day,day).get(0);
            assertEquals("ON_LEAVE",summary.get("status"));
            assertEquals(0,((Number)summary.get("late")).intValue());
            assertEquals(0,((Number)summary.get("early")).intValue());
            assertEquals(4,attendance.listMyPunches(employee,day).size());

            assertThrows(ManagementException.class,()->attendance.appendPunch(9920L,9922,null,day,day.atTime(18,0),
                    "INVALID","WEB",null,null,null,null));
            status.setRollbackOnly();
        });
    }
}
