package com.xinhe.nexus.management.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class PersonalProfileServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void blankApprovalFieldsAreDroppedBeforeCreatingRequest() throws Exception {
        PersonalProfileService service = new PersonalProfileService(mock(JdbcTemplate.class), new ObjectMapper(), new ProfileDataCrypto("test-profile-key"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("gender", "FEMALE");
        input.put("birthDate", "   ");
        input.put("address", "");
        input.put("educations", List.of(Map.of("schoolName", "", "educationLevel", "")));

        Method normalize = PersonalProfileService.class.getDeclaredMethod("normalizeApprovalFields", Map.class);
        normalize.setAccessible(true);

        assertEquals(Map.of("gender", "FEMALE"), normalize.invoke(service, input));
    }
}
