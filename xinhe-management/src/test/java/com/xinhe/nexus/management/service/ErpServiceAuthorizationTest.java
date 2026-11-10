package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class ErpServiceAuthorizationTest {
    private JdbcTemplate db;
    private ErpService service;

    @BeforeEach
    void setup() {
        db = mock(JdbcTemplate.class);
        service = new ErpService(db);
    }

    @Test
    void subsidiaryCompanyCanUseErpProductApiWithPermission() {
        ManagementUser subsidiary = user(false, List.of("erp:product:view"));
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        List<java.util.Map<String, Object>> products =
                service.products(subsidiary, null, null, null, null, null, null);

        assertEquals(List.of(), products);
        verify(db).queryForList(anyString(), eq(new Object[]{1L}));
    }

    @Test
    void companyUserWithoutProductPermissionCannotReadProducts() {
        ManagementUser user = user(false, List.of("crm:customer:view"));

        ManagementException exception = assertThrows(
                ManagementException.class, () -> service.products(user, null, null, null, null, null, null));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verifyNoInteractions(db);
    }

    @Test
    void invalidProductInputIsRejectedBeforeDatabaseWrite() {
        ManagementUser user = user(true, List.of("erp:product:create"));

        ManagementException missingName = assertThrows(
                ManagementException.class,
                () -> service.createProduct(user, new ErpService.ProductInput(" ", 1L, null, null, BigDecimal.ONE, null, null)));
        ManagementException negativePrice = assertThrows(
                ManagementException.class,
                () -> service.createProduct(user, new ErpService.ProductInput("商品", 1L, null, null, new BigDecimal("-0.01"), null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, missingName.status());
        assertEquals(HttpStatus.BAD_REQUEST, negativePrice.status());
        verifyNoInteractions(db);
    }

    @Test
    void nonApproverCannotDecideAnOrder() {
        ManagementUser user = user(true, List.of("erp:sales-order:approve"));
        when(db.queryForMap(anyString(), eq(42L), eq(1L)))
                .thenReturn(java.util.Map.of("id", 42L, "status", "PENDING_APPROVAL", "approverUserId", 200L));

        ManagementException exception = assertThrows(
                ManagementException.class, () -> service.decideOrder(user, 42L, true, "通过"));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
    }

    @Test
    void invalidOrderInputIsRejectedBeforeDatabaseWrite() {
        ManagementUser user = user(true, List.of("erp:sales-order:create"));
        ErpService.OrderInput emptyLines = new ErpService.OrderInput("客户", null, null, null, null, null, List.of());

        ManagementException exception = assertThrows(
                ManagementException.class, () -> service.submitOrder(user, emptyLines));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verifyNoInteractions(db);
    }

    private ManagementUser user(boolean headquarters, List<String> permissions) {
        return new ManagementUser(100L, 1L, "芯河智枢总公司", "erp_test", "ERP测试用户",
                List.of("COMPANY_USER"), permissions, false, 1L, headquarters);
    }
}
