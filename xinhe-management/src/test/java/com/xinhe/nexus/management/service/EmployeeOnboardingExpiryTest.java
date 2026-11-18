package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;

class EmployeeOnboardingExpiryTest {
    private EmployeeOnboardingService service() {
        return new EmployeeOnboardingService(
                mock(JdbcTemplate.class),
                mock(PasswordEncoder.class),
                new ProfileDataCrypto("test-profile-key"),
                "test-onboarding-key");
    }

    @Test
    void expiryDaysDefaultAndBoundsAreNormalized() throws Exception {
        Method normalize = EmployeeOnboardingService.class.getDeclaredMethod("normalizeExpiryDays", Integer.class);
        normalize.setAccessible(true);
        EmployeeOnboardingService service = service();

        assertEquals(7, normalize.invoke(service, new Object[] { null }));
        assertEquals(1, normalize.invoke(service, -5));
        assertEquals(30, normalize.invoke(service, 99));
        assertEquals(14, normalize.invoke(service, 14));
    }

    @Test
    void expiryCleanupRunsOnSchedule() throws Exception {
        Method cleanup = EmployeeOnboardingService.class.getMethod("expirePendingInvitations");
        Scheduled scheduled = cleanup.getAnnotation(Scheduled.class);

        assertTrue(scheduled != null);
        assertEquals("${xinhe.onboarding.expiry-cleanup-ms:300000}", scheduled.fixedDelayString());
    }
}
