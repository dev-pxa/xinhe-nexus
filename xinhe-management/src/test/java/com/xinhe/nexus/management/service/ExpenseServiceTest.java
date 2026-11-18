package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ExpenseServiceTest {
    private JdbcTemplate db;
    private ExpenseService service;

    @BeforeEach
    void setup() {
        db = mock(JdbcTemplate.class);
        service = new ExpenseService(db, "/tmp/xinhe-expense-test");
    }

    @Test
    void departmentManagerCanApproveFirstNodeWithoutFinalApprovalPermission() {
        ManagementUser manager = user(20L, List.of());
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(rawClaim("PENDING", 20L, 10L, 3));
        when(db.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(101L);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);

        service.review(manager, 88L, "APPROVE", "同意", 3);

        verify(db).update(org.mockito.ArgumentMatchers.contains("SET status = 'REVIEWING'"), any(Object[].class));
    }

    @Test
    void finalApprovalRequiresConfiguredPermission() {
        ManagementUser ordinaryUser = user(21L, List.of());
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(rawClaim("REVIEWING", null, 10L, 4));

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.review(ordinaryUser, 88L, "APPROVE", "", 4));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        assertEquals("当前账号未配置报销最终审批权限", exception.getMessage());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void configuredFinanceUserCanCompleteFinalApproval() {
        ManagementUser finance = user(22L, List.of("oa:expense:approve"));
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(rawClaim("REVIEWING", null, 10L, 4));
        when(db.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(102L);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);

        service.review(finance, 88L, "APPROVE", "票据无误", 4);

        verify(db).update(org.mockito.ArgumentMatchers.contains("SET status = 'APPROVED'"), any(Object[].class));
    }

    @Test
    void applicantCannotApproveOwnExpenseEvenWithFinalPermission() {
        ManagementUser applicant = user(10L, List.of("oa:expense:approve"));
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(rawClaim("REVIEWING", null, 10L, 4));

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.review(applicant, 88L, "APPROVE", "", 4));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        assertEquals("申请人不能审批自己的报销单", exception.getMessage());
    }

    @Test
    void staleApprovalVersionIsRejectedBeforeChangingNode() {
        ManagementUser finance = user(22L, List.of("oa:expense:approve"));
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(rawClaim("REVIEWING", null, 10L, 5));

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.review(finance, 88L, "APPROVE", "", 4));

        assertEquals(HttpStatus.CONFLICT, exception.status());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void pendingExpenseWithoutApprovalActionCanBeWithdrawnToDraft() {
        ManagementUser applicant = user(10L, List.of());
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(readableClaim("PENDING", 3)));
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("count", 0L));
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);

        service.withdraw(applicant, 88L, 3);

        verify(db).update(org.mockito.ArgumentMatchers.contains("SET status = 'DRAFT'"), any(Object[].class));
        verify(db).update(org.mockito.ArgumentMatchers.contains("SET status = 'CANCELLED'"), any(Object[].class));
    }

    @Test
    void reviewingExpenseCannotBeWithdrawn() {
        ManagementUser applicant = user(10L, List.of());
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(readableClaim("REVIEWING", 4)));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.withdraw(applicant, 88L, 4));

        assertEquals("只有尚未发生审批的报销单可以撤回", exception.getMessage());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void pendingExpenseWithRecordedApprovalActionCannotBeWithdrawn() {
        ManagementUser applicant = user(10L, List.of());
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(readableClaim("PENDING", 3)));
        when(db.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("count", 1L));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.withdraw(applicant, 88L, 3));

        assertEquals("报销单已发生审批，不能撤回", exception.getMessage());
    }

    private ManagementUser user(long id, List<String> permissions) {
        return new ManagementUser(id, 1L, "测试公司", "user" + id, "用户" + id,
                List.of("COMPANY_USER"), permissions, false, 1L, false);
    }

    private Map<String, Object> rawClaim(String status, Long currentReviewerId, long applicantId, int version) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("id", 88L);
        claim.put("company_id", 1L);
        claim.put("applicant_user_id", applicantId);
        claim.put("status", status);
        claim.put("current_reviewer_id", currentReviewerId);
        claim.put("approval_round", 1);
        claim.put("version", version);
        return claim;
    }

    private Map<String, Object> readableClaim(String status, int version) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("id", 88L);
        claim.put("companyId", 1L);
        claim.put("applicantUserId", 10L);
        claim.put("status", status);
        claim.put("currentReviewerId", 20L);
        claim.put("approvalRound", 1);
        claim.put("version", version);
        return claim;
    }
}
