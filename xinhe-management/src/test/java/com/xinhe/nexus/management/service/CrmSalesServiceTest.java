package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class CrmSalesServiceTest {

    @Test
    void applicantCannotApproveOwnSalesOrderEvenAsAdministrator() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(),any(Object[].class)))
                .thenReturn(List.of(Map.of("approval_status","PENDING_APPROVAL","created_by",10L)));
        CrmSalesService service = new CrmSalesService(db);
        ManagementUser applicant = new ManagementUser(10L,8L,"公司","user","申请人",
                List.of("COMPANY_ADMIN"),List.of("*"),false,1L);
        org.junit.jupiter.api.Assertions.assertThrows(ManagementException.class,
                () -> service.decide(applicant,88L,true,""));
    }

    @Test
    void companyAdminOrderListKeepsScopeSeparatedFromAndKeyword() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        CrmSalesService service = new CrmSalesService(db);
        ManagementUser admin = new ManagementUser(
            1L, 8L, "测试公司", "admin", "管理员",
            List.of("COMPANY_ADMIN"), List.of("*"), false, 1L
        );

        service.orders(admin, null, null, null, null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("AND (1=1)"));
        assertFalse(sql.getValue().contains("AND1=1"));
    }

    @Test
    void pendingApprovalListIncludesAllManagersInTheAncestorOrganizationChain() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        CrmSalesService service = new CrmSalesService(db);
        ManagementUser upperManager = new ManagementUser(
            99L, 8L, "测试公司", "upper_manager", "上级负责人",
            List.of("COMPANY_USER"), List.of("crm:sales-order:approve"), false, 1L
        );

        service.pendingApprovals(upperManager);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("emporg.org_path LIKE CONCAT(mgrorg.org_path,'%')"));
        assertTrue(sql.getValue().contains("mgrorg.manager_user_id=99"));
        assertTrue(sql.getValue().contains("o.approver_user_id=99"));
        assertFalse(sql.getValue().contains("o.approver_user_id=?"));
    }

    @Test
    void approvalRichTextIsSanitizedBeforePersistence() {
        String sanitized = CrmSalesService.sanitizeRichText(
            "<p onclick=\"steal()\">同意<strong>发货</strong><img src=x onerror=steal()></p>" +
            "<script>alert(1)</script><ul><li>库存充足</li></ul>"
        );

        assertEquals("<p>同意<strong>发货</strong></p><ul><li>库存充足</li></ul>", sanitized);
    }

    @Test
    void upperOrganizationManagerCanApproveOrder() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), any(Object[].class)))
            .thenReturn(List.of(java.util.Map.of("id", 42L)));
        CrmSalesService service = new CrmSalesService(db);
        ManagementUser upperManager = new ManagementUser(
            99L, 8L, "测试公司", "upper_manager", "上级负责人",
            List.of("COMPANY_USER"), List.of("crm:sales-order:approve"), false, 1L
        );

        assertTrue(service.canManageOrder(upperManager, 42L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("mgrorg.manager_user_id=99"));
        assertTrue(sql.getValue().contains("emporg.org_path LIKE CONCAT(mgrorg.org_path,'%')"));
    }

    @Test
    void upperOrganizationManagerCanRejectWhenNotTheDirectApprover() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        java.util.Map<String,Object> pending = new java.util.HashMap<>();
        pending.put("id", 42L);
        pending.put("approval_status", "PENDING_APPROVAL");
        pending.put("approver_user_id", 7L);
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(pending));
        CrmSalesService service = new CrmSalesService(db);
        ManagementUser upperManager = new ManagementUser(
            99L, 8L, "测试公司", "upper_manager", "上级负责人",
            List.of("COMPANY_USER"),
            List.of("crm:sales-order:view", "crm:sales-order:approve"), false, 1L
        );

        service.decide(upperManager, 42L, false, "<p><strong>上级负责人驳回</strong></p>");

        verify(db).update(
            contains("approval_status='REJECTED',approval_comment=?"),
            org.mockito.ArgumentMatchers.eq("<p><strong>上级负责人驳回</strong></p>"),
            org.mockito.ArgumentMatchers.eq(99L),
            org.mockito.ArgumentMatchers.eq(8L),
            org.mockito.ArgumentMatchers.eq(42L)
        );
    }
}
