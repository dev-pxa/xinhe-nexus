package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.math.BigInteger;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class ManagementSystemServiceAuthorizationTest {
    private JdbcTemplate jdbcTemplate;
    private ManagementSystemService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ManagementSystemService(jdbcTemplate, mock(PasswordEncoder.class));
    }

    @Test
    void companyUserCannotReadAnotherCompanyOrganizations() {
        ManagementUser user = new ManagementUser(
                10L, 100L, "公司A", "user", "用户",
                List.of("COMPANY_ADMIN"), List.of("*"), false, 1L);

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.listOrganizations(user, 200L));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void platformAdministratorMustSelectCompanyForScopedData() {
        ManagementUser root = new ManagementUser(
                1L, null, null, "root", "平台管理员",
                List.of("PLATFORM_ADMIN"), List.of("*"), true, 1L);

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.listOrganizations(root, null));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void regularUserWithoutPermissionCannotCreateOrganization() {
        ManagementUser user = new ManagementUser(
                10L, 100L, "公司A", "user", "用户",
                List.of("COMPANY_USER"), List.of("system:org:view"), false, 1L);

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.createOrganization(user, null,
                        new ManagementDtos.OrgSave("研发部", null, null, null, null, "ENABLED", null)));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void organizationRejectsInvalidContactPhoneBeforeWritingData() {
        ManagementUser user = new ManagementUser(
                10L, 100L, "公司A", "admin", "管理员",
                List.of("COMPANY_ADMIN"), List.of("*"), false, 1L);

        ManagementException exception = assertThrows(
                ManagementException.class,
                () -> service.createOrganization(user, null,
                        new ManagementDtos.OrgSave(
                                "研发部", 1L, null, "contact@example.com", null, "ENABLED", null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("联系电话格式不正确，可填写国际号码、座机或手机号", exception.getMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void organizationAcceptsInternationalLandlineAndMobileFormats() {
        assertEquals("+1 (415) 555-2671", service.normalizeContactPhone("  +1 (415) 555-2671  "));
        assertEquals("+86 138 0013 8000", service.normalizeContactPhone("+86 138 0013 8000"));
        assertEquals("010-12345678", service.normalizeContactPhone("010-12345678"));
        assertNull(service.normalizeContactPhone("  "));
    }

    @Test
    void organizationRejectsMalformedContactPhoneFormats() {
        for (String phone : List.of("1234", "++86 13800138000", "+86 (13800138000", "12345#678")) {
            assertThrows(ManagementException.class, () -> service.normalizeContactPhone(phone));
        }
    }

    @Test
    void unsignedBigintParentIdConvertsWithoutClassCastException() {
        assertEquals(1L, service.asNullableLong(BigInteger.ONE));
        assertEquals(4L, service.asNullableLong(Integer.valueOf(4)));
        assertNull(service.asNullableLong(null));
    }

    @Test
    void positionListWithOrganizationUsesValidParameterizedSql() {
        ManagementUser user = new ManagementUser(
                10L, 100L, "公司A", "admin", "管理员",
                List.of("COMPANY_ADMIN"), List.of("*"), false, 1L);

        service.listPositions(user, null, 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(100L), eq(1L), eq(1L));
        String normalizedSql = sql.getValue().replaceAll("\\s+", " ");
        assertTrue(normalizedSql.contains("AND (? IS NULL OR p.org_unit_id = ?) GROUP BY"));
        assertFalse(sql.getValue().contains("?GROUP BY"));
    }
}
