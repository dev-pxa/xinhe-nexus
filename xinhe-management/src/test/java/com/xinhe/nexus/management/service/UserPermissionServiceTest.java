package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xinhe.nexus.management.auth.ManagementUser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserPermissionServiceTest {
    private JdbcTemplate jdbcTemplate;
    private ManagementSystemService service;
    private ManagementUser admin;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new ManagementSystemService(jdbcTemplate, mock(PasswordEncoder.class));
        admin = new ManagementUser(1L, 100L, "公司A", "admin", "管理员",
                List.of("COMPANY_ADMIN"), List.of("*"), false, 1L);
    }

    @Test
    void regularUserCannotConfigureDirectPermissions() {
        ManagementUser user = new ManagementUser(2L, 100L, "公司A", "user", "用户",
                List.of("COMPANY_USER"), List.of("system:user:permission"), false, 1L);

        ManagementException exception = assertThrows(ManagementException.class,
                () -> service.replaceUserPermissions(user, 20L,
                        new ManagementDtos.UserPermissions(List.of(3L))));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void directPermissionSaveRemovesInheritedPermissionAndInvalidatesSession() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(20L)))
                .thenReturn(List.of(100L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0, 1, 1);
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(100L), eq(20L)))
                .thenReturn(List.of(3L));

        service.replaceUserPermissions(admin, 20L,
                new ManagementDtos.UserPermissions(List.of(3L, 4L, 4L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).update(sql.capture(), any(Object[].class));
        long insertedDirectPermissions = sql.getAllValues().stream()
                .filter(value -> value.contains("INSERT INTO sys_user_permission"))
                .count();
        assertEquals(1, insertedDirectPermissions);
        verify(jdbcTemplate).update(
                "UPDATE sys_user SET auth_version=auth_version+1 WHERE id=?", 20L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ordinaryUserCannotBeSavedWithoutPosition() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(20L)))
                .thenReturn(List.of(100L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        ManagementException exception = assertThrows(ManagementException.class,
                () -> service.replaceUserPosition(admin, 100L, 20L,
                        new ManagementDtos.UserPosition(null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        assertEquals("普通用户必须选择岗位", exception.getMessage());
        verify(jdbcTemplate, never()).update(
                eq("DELETE FROM sys_user_position WHERE company_id = ? AND user_id = ?"),
                anyLong(), anyLong());
    }
}
