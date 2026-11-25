package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.xinhe.nexus.management.auth.ManagementUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="XINHE_LEAVE_TEST_JDBC_URL",matches=".+")
class ApprovalChainMySqlTest {
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static ExpenseService expense;
    private static final ManagementUser ADMIN = actor(12001,true);
    private static final ManagementUser HEAD = actor(12002,false);
    private static final ManagementUser EMPLOYEE = actor(12003,false);
    private static final ManagementUser FINANCE = new ManagementUser(12005,12000L,"测试公司","finance","财务",
            List.of("COMPANY_USER"),List.of("oa:expense:approve"),false,1L);

    @BeforeAll
    static void connect() {
        var source = new DriverManagerDataSource(System.getenv("XINHE_LEAVE_TEST_JDBC_URL"),
                System.getenv("XINHE_LEAVE_TEST_DB_USER"),System.getenv("XINHE_LEAVE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        db=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        expense=new ExpenseService(db,"/tmp/approval-chain-test");
    }

    private void seed() {
        db.update("INSERT INTO sys_company(id,company_code,company_name) VALUES (12000,'APPROVAL_TEST','审批测试')");
        db.update("""
                INSERT INTO sys_user(id,username,password_hash,display_name,status) VALUES
                (12001,'approval-admin','test','公司管理员','ENABLED'),
                (12002,'approval-head','test','部门负责人','ENABLED'),
                (12003,'approval-employee','test','员工','ENABLED'),
                (12004,'approval-inactive','test','停用负责人','DISABLED'),
                (12005,'approval-finance','test','财务','ENABLED')
                """);
        db.update("""
                INSERT INTO sys_user_company(company_id,user_id,is_company_admin)
                VALUES (12000,12001,1),(12000,12002,0),(12000,12003,0),(12000,12004,0),(12000,12005,0)
                """);
        db.update("""
                INSERT INTO sys_org_unit(id,company_id,parent_id,org_code,org_name,org_path,org_level,manager_user_id)
                VALUES (12000,12000,NULL,'ROOT','公司','/12000/',1,12001),
                       (12001,12000,12000,'DEPT','部门','/12000/12001/',2,12002)
                """);
        db.update("""
                INSERT INTO sys_position(id,company_id,org_unit_id,position_code,position_name) VALUES
                (12000,12000,12000,'ROOT','公司管理员'),(12001,12000,12001,'HEAD','部门负责人'),
                (12002,12000,12001,'EMPLOYEE','员工')
                """);
        db.update("UPDATE sys_org_unit SET manager_position_id=12001 WHERE id=12001");
        db.update("""
                INSERT INTO sys_user_position(company_id,user_id,position_id)
                VALUES (12000,12001,12000),(12000,12002,12001),(12000,12003,12002)
                """);
        db.update("""
                INSERT INTO sys_user_permission(company_id,user_id,permission_id)
                SELECT 12000,12005,id FROM sys_permission WHERE permission_code='oa:expense:approve'
                """);
    }

    private long claim(ManagementUser user) {
        var created=expense.create(user);
        long id=((Number)created.get("id")).longValue();
        expense.save(user,id,new ExpenseService.SaveRequest(List.of(new ExpenseService.ItemRequest(
                null,"OTHER",LocalDate.now(),"测试地点",BigDecimal.TEN,"测试"))));
        return id;
    }

    private int version(ManagementUser user,long id) {
        return ((Number)expense.detail(user,id).get("version")).intValue();
    }

    @Test
    void departmentHeadUsesParentFinalReviewerAndCompletesInOneAction() {
        tx.executeWithoutResult(status -> {
            seed();
            long id=claim(HEAD);
            var preview=expense.detail(HEAD,id);
            var nodes=(List<Map<String,Object>>)preview.get("approvalNodes");
            assertEquals(1,nodes.size());
            assertEquals("FINAL_APPROVER",nodes.get(0).get("nodeType"));
            assertEquals(ADMIN.id(),((Number)nodes.get(0).get("reviewerUserId")).longValue());
            expense.submit(HEAD,id,version(HEAD,id));
            assertEquals("REVIEWING",expense.detail(HEAD,id).get("status"));
            assertEquals(true,expense.detail(HEAD,id).get("canWithdraw"));
            assertTrue(expense.listPending(ADMIN).stream().anyMatch(row->((Number)row.get("id")).longValue()==id));
            assertTrue(expense.listPending(FINANCE).isEmpty());
            assertThrows(ManagementException.class,()->expense.detail(FINANCE,id));
            assertThrows(ManagementException.class,()->expense.review(FINANCE,id,"APPROVE","",version(HEAD,id)));

            expense.withdraw(HEAD,id,version(HEAD,id));
            expense.submit(HEAD,id,version(HEAD,id));
            assertEquals(2,((Number)expense.detail(HEAD,id).get("approvalRound")).intValue());
            expense.review(ADMIN,id,"APPROVE","同意",version(HEAD,id));
            var result=expense.detail(HEAD,id);
            assertEquals("APPROVED",result.get("status"));
            assertEquals(false,result.get("canWithdraw"));
            assertEquals(1L,db.queryForObject("SELECT COUNT(*) FROM oa_expense_approval_node WHERE claim_id=? AND approval_round=2 AND status='APPROVED'",Long.class,id));
            assertEquals(12001L,db.queryForObject("SELECT applicant_org_id FROM oa_expense_claim WHERE id=?",Long.class,id));
            status.setRollbackOnly();
        });
    }

    @Test
    void ordinaryEmployeeStillUsesTwoStagesAndRejectedRemainingNodesAreClosed() {
        tx.executeWithoutResult(status -> {
            seed();
            long id=claim(EMPLOYEE);
            expense.submit(EMPLOYEE,id,version(EMPLOYEE,id));
            assertEquals("PENDING",expense.detail(EMPLOYEE,id).get("status"));
            expense.review(HEAD,id,"APPROVE","同意",version(EMPLOYEE,id));
            assertEquals("REVIEWING",expense.detail(EMPLOYEE,id).get("status"));
            assertEquals(false,expense.detail(EMPLOYEE,id).get("canWithdraw"));
            expense.review(FINANCE,id,"APPROVE","通过",version(EMPLOYEE,id));
            assertEquals("APPROVED",expense.detail(EMPLOYEE,id).get("status"));

            long rejected=claim(EMPLOYEE);
            expense.submit(EMPLOYEE,rejected,version(EMPLOYEE,rejected));
            expense.review(HEAD,rejected,"REJECT","补充材料",version(EMPLOYEE,rejected));
            assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM oa_expense_approval_node WHERE claim_id=? AND status='PENDING'",Long.class,rejected));
            status.setRollbackOnly();
        });
    }

    @Test
    void skipsInactiveSelfAndUnauthorizedReviewersAndBlocksSelfApproval() {
        tx.executeWithoutResult(status -> {
            seed();
            assertEquals(12002L,((Number)OrganizationApprovers.nearest(db,12000,EMPLOYEE.id(),null).get("id")).longValue());
            assertEquals(12001L,((Number)OrganizationApprovers.nearest(db,12000,HEAD.id(),null).get("id")).longValue());
            // Department head has no correction approval permission, so the authorized parent is selected.
            assertEquals(12001L,((Number)OrganizationApprovers.nearest(db,12000,EMPLOYEE.id(),"oa:attendance:correction:review").get("id")).longValue());
            db.update("UPDATE sys_user SET status='DISABLED' WHERE id=12002");
            assertEquals(12001L,((Number)OrganizationApprovers.nearest(db,12000,EMPLOYEE.id(),null).get("id")).longValue());
            db.update("UPDATE sys_user SET status='ENABLED' WHERE id=12002");
            assertNull(OrganizationApprovers.nearest(db,12000,ADMIN.id(),null));
            assertNull(OrganizationApprovers.nearest(db,99999,EMPLOYEE.id(),null));

            long id=claim(EMPLOYEE);
            expense.submit(EMPLOYEE,id,version(EMPLOYEE,id));
            // A legacy node routed to its own applicant must remain forbidden.
            db.update("UPDATE oa_expense_claim SET current_reviewer_id=? WHERE id=?",EMPLOYEE.id(),id);
            assertThrows(ManagementException.class,()->expense.review(EMPLOYEE,id,"APPROVE","",version(EMPLOYEE,id)));
            status.setRollbackOnly();
        });
    }

    @Test
    void managerWhoGainsFinalPermissionFinishesAnExistingFirstStage() {
        tx.executeWithoutResult(status -> {
            seed();
            long id=claim(EMPLOYEE);
            expense.submit(EMPLOYEE,id,version(EMPLOYEE,id));
            var promoted = new ManagementUser(HEAD.id(),12000L,"公司","head","部门负责人",
                    List.of("COMPANY_USER"),List.of("oa:expense:approve"),false,1L);
            expense.review(promoted,id,"APPROVE","一次完成",version(EMPLOYEE,id));
            assertEquals("APPROVED",expense.detail(EMPLOYEE,id).get("status"));
            assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM oa_expense_approval_node WHERE claim_id=? AND status='PENDING'",Long.class,id));
            status.setRollbackOnly();
        });
    }

    @Test
    void departmentManagerWithExplicitFinalPermissionAlsoUsesOneNode() {
        tx.executeWithoutResult(status -> {
            seed();
            db.update("""
                    INSERT INTO sys_user_permission(company_id,user_id,permission_id)
                    SELECT 12000,12002,id FROM sys_permission WHERE permission_code='oa:expense:approve'
                    """);
            long id=claim(EMPLOYEE);
            var nodes=(List<Map<String,Object>>)expense.detail(EMPLOYEE,id).get("approvalNodes");
            assertEquals(1,nodes.size());
            assertEquals("FINAL_APPROVER",nodes.get(0).get("nodeType"));
            expense.submit(EMPLOYEE,id,version(EMPLOYEE,id));
            var finalManager=new ManagementUser(HEAD.id(),12000L,"公司","head","部门负责人",
                    List.of("COMPANY_USER"),List.of("oa:expense:approve"),false,1L);
            expense.review(finalManager,id,"APPROVE","终审",version(EMPLOYEE,id));
            assertEquals("APPROVED",expense.detail(EMPLOYEE,id).get("status"));
            status.setRollbackOnly();
        });
    }

    private static ManagementUser actor(long id,boolean admin) {
        return new ManagementUser(id,12000L,"审批测试","approval-"+id,"用户"+id,
                List.of(admin?"COMPANY_ADMIN":"COMPANY_USER"),List.of(),false,1L);
    }
}
