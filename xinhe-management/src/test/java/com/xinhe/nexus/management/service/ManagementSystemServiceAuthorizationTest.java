package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.util.List;
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
}
