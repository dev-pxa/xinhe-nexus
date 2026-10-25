package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.AttendanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oa/attendance")
public class AttendanceController {
    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    @PostMapping("/punch")
    public ApiResponse<Map<String, Object>> punch(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            HttpServletRequest request,
            @RequestBody(required = false) PunchRequest body) {
        return ApiResponse.success(service.punch(user, request.getRemoteAddr(), request.getHeader("User-Agent"),
                body == null ? null : body.remark()));
    }

    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ApiResponse.success(service.listMine(user, from, to));
    }

    @GetMapping("/records")
    public ApiResponse<List<Map<String, Object>>> records(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orgUnitId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.listAll(user, companyId, from, to, userId, orgUnitId, keyword));
    }

    @PostMapping("/records/punch")
    public ApiResponse<Map<String, Object>> adminPunch(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            HttpServletRequest request,
            @RequestBody AdminPunchRequest body) {
        return ApiResponse.success(service.adminPunch(user, body.companyId(), body.userId(), body.punchTime(),
                request.getRemoteAddr(), request.getHeader("User-Agent"), body.remark()));
    }

    public record PunchRequest(String remark) {}

    public record AdminPunchRequest(Long companyId, long userId, String punchTime, String remark) {}
}
