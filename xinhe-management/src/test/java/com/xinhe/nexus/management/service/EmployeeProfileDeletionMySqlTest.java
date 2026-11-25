package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "XINHE_LEAVE_TEST_JDBC_URL", matches = ".+")
class EmployeeProfileDeletionMySqlTest {
    @Test
    void archivesProfilesAndRevokesLinkedMembershipWithoutLosingHistory() {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"), System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var passwordEncoder = mock(PasswordEncoder.class);
        var management = new ManagementSystemService(db, passwordEncoder);
        var onboarding = new EmployeeOnboardingService(db, passwordEncoder, new ProfileDataCrypto("test"), "test");
        var admin = actor(9301, 9300);
        transaction.executeWithoutResult(tx -> {
            db.update("INSERT INTO sys_company(id,company_code,company_name) VALUES (9300,'PROFILE_DELETE','测试公司')");
            db.update("""
                    INSERT INTO sys_user(id,username,password_hash,display_name) VALUES
                        (9301,'profile-delete-admin','test','管理员'), (9302,'profile-delete-account','test','待删除员工')
                    """);
            db.update("""
                    INSERT INTO sys_user_company(company_id,user_id,is_company_admin,status)
                    VALUES (9300,9301,1,'ENABLED'),(9300,9302,0,'DISABLED')
                    """);
            db.update("""
                    INSERT INTO sys_org_unit(id,company_id,org_code,org_name,org_path,org_level)
                    VALUES (9300,9300,'DELETE_ORG','测试部门','/9300/',1)
                    """);
            db.update("""
                    INSERT INTO sys_position(id,company_id,org_unit_id,position_code,position_name)
                    VALUES (9300,9300,9300,'DELETE_POSITION','测试岗位')
                    """);
            db.update("""
                    INSERT INTO hr_onboarding_invitation(id,company_id,candidate_name,department_id,position_id,token_hash,status,expires_at)
                    VALUES (9301,9300,'管理员',9300,9300,SHA2('test-admin-link',256),'SUBMITTED','2099-01-01'),
                           (9302,9300,'待删除员工',9300,9300,SHA2('test-employee-link',256),'SUBMITTED','2099-01-01'),
                           (9303,9300,'无账号员工',9300,9300,SHA2('test-orphan-link',256),'SUBMITTED','2099-01-01')
                    """);
            db.update("""
                    INSERT INTO hr_employee_profile(id,invitation_id,user_id,company_id,candidate_name) VALUES
                        (9301,9301,9301,9300,'管理员'),(9302,9302,9302,9300,'待删除员工'),(9303,9303,9399,9300,'无账号员工')
                    """);
            db.update("""
                    INSERT INTO hr_employee_profile_change_request(company_id,profile_id,user_id,applicant_user_id,
                        approver_user_id,status,before_data,after_data) VALUES
                        (9300,9302,9302,9302,9301,'PENDING',JSON_OBJECT(),JSON_OBJECT()),
                        (9300,9302,9302,9302,9301,'APPROVED',JSON_OBJECT(),JSON_OBJECT())
                    """);

            assertEquals(3, onboarding.listInvitations(admin, null).size());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ManagementException.class,
                    () -> management.deleteEmployeeProfile(actor(9401, 9400), 9302)).status());
            assertThrows(ManagementException.class, () -> management.deleteEmployeeProfile(admin, 9301));
            management.deleteEmployeeProfile(admin, 9302);
            management.deleteEmployeeProfile(admin, 9303);

            assertEquals(1, onboarding.listInvitations(admin, null).size());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ManagementException.class,
                    () -> onboarding.publicForm("test-employee-link")).status());
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=9302 AND deleted_at IS NOT NULL", Integer.class));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_user_company WHERE user_id=9302 AND deleted_at IS NOT NULL", Integer.class));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM hr_employee_profile WHERE id IN (9302,9303) AND deleted_at IS NOT NULL", Integer.class));
            assertEquals(List.of("APPROVED", "CANCELLED"), db.queryForList(
                    "SELECT status FROM hr_employee_profile_change_request WHERE profile_id=9302 ORDER BY status",
                    String.class));
            db.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES ('profile-delete-account','test','重新录入')");
            tx.setRollbackOnly();
        });
    }

    @Test
    void phoneLookupRejectsDuplicatesAndPhoneWritesAreUnique() {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"), System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        var repository = new com.xinhe.nexus.management.auth.UserAccountRepository(db, "XINHE");
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(tx -> {
            db.update("""
                    INSERT INTO sys_user(id,username,password_hash,display_name,phone) VALUES
                      (9701,'phone-test-a','test','甲','13999990001'),
                      (9702,'phone-test-b','test','乙','13999990001'),
                      (9703,'phone-test-c','test','丙','13999990002')
                    """);
            org.junit.jupiter.api.Assertions.assertNull(repository.findByPhone("13999990001"));
            assertEquals(9703L, repository.findByPhone("13999990002").id());
            assertThrows(ManagementException.class, () -> repository.updateProfile(9701, "甲", "13999990002", null));
            repository.updateProfile(9703, "丙", " 13999990002 ", null);
            assertEquals("13999990002", db.queryForObject("SELECT phone FROM sys_user WHERE id=9703", String.class));
            assertThrows(IllegalArgumentException.class, () -> repository.updateProfile(9703, "丙", "invalid", null));
            db.update("UPDATE sys_user SET status='DISABLED' WHERE id=9703");
            assertThrows(ManagementException.class, () -> repository.updateProfile(9701, "甲", "13999990002", null));
            db.update("UPDATE sys_user SET deleted_at=NOW() WHERE id=9703");
            repository.updateProfile(9701, "甲", "13999990002", null);
            assertEquals(9701L, repository.findByPhone("13999990002").id());
            tx.setRollbackOnly();
        });
    }

    @Test
    void concurrentPhoneChangesAllowOnlyOneAccount() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"), System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        var db = new JdbcTemplate(source);
        db.update("""
                INSERT INTO sys_user(id,username,password_hash,display_name) VALUES
                  (9801,'phone-race-a','test','甲'),(9802,'phone-race-b','test','乙')
                """);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (long id : List.of(9801L, 9802L)) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
                    try {
                        tx.executeWithoutResult(status -> {
                            var repository = new com.xinhe.nexus.management.auth.UserAccountRepository(db, "XINHE");
                            repository.updateProfile(id, "测试", "13999990003", null);
                        });
                        return true;
                    } catch (ManagementException conflict) {
                        assertEquals(HttpStatus.CONFLICT, conflict.status());
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(10, java.util.concurrent.TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, db.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE phone='13999990003' AND deleted_at IS NULL", Integer.class));
        } finally {
            pool.shutdownNow();
            db.update("DELETE FROM sys_user WHERE id IN (9801,9802)");
        }
    }

    private static ManagementUser actor(long id, long company) {
        return new ManagementUser(id, company, "测试公司", "profile-delete-admin", "管理员",
                List.of("COMPANY_ADMIN"), List.of(), false, 1L);
    }
}
