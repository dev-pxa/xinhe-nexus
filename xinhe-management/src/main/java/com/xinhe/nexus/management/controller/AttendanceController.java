package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.AttendanceService;
import com.xinhe.nexus.management.service.AttendanceService.CorrectionInput;
import com.xinhe.nexus.management.service.AttendanceService.PunchInput;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
            @RequestBody(required = false) PunchInput body) {
        return ApiResponse.success(service.punch(user, request.getRemoteAddr(), request.getHeader("User-Agent"), body));
    }

    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ApiResponse.success(service.listMine(user, from, to));
    }

    @GetMapping("/my/punches")
    public ApiResponse<List<Map<String, Object>>> myPunches(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam LocalDate date) {
        return ApiResponse.success(service.listMyPunches(user, date));
    }

    @GetMapping("/records")
    public ApiResponse<Map<String, Object>> records(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long orgUnitId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.listAll(user, companyId, from, to, userId, orgUnitId, keyword, page, pageSize));
    }

    @GetMapping("/records/punches")
    public ApiResponse<List<Map<String, Object>>> punches(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @RequestParam(required = false) Long companyId,
            @RequestParam long userId,
            @RequestParam LocalDate date) {
        return ApiResponse.success(service.listPunches(user, companyId, userId, date));
    }

    @PostMapping("/records/punch")
    public ApiResponse<Map<String, Object>> adminPunch(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            HttpServletRequest request,
            @RequestBody AdminPunchRequest body) {
        return ApiResponse.success(service.adminPunch(user, body.companyId(), body.userId(), body.punchTime(),
                request.getRemoteAddr(), request.getHeader("User-Agent"), new AttendanceService.AdminPunchInput(body.latitude(),body.longitude(),body.accuracy(),body.locationSource(),body.capturedAt(),body.remark(),body.punchType())));
    }

    @PostMapping("/correction-requests")
    public ApiResponse<List<Map<String,Object>>> createCorrection(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,@RequestBody CorrectionInput body){return ApiResponse.success(service.createCorrection(user,body));}
    @GetMapping("/correction-requests/mine")
    public ApiResponse<List<Map<String,Object>>> myCorrections(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user){return ApiResponse.success(service.listMyCorrections(user));}
    @GetMapping("/correction-requests/pending")
    public ApiResponse<List<Map<String,Object>>> pendingCorrections(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user){return ApiResponse.success(service.listPendingCorrections(user));}
    @PostMapping("/correction-requests/{id}/decision")
    public ApiResponse<Map<String,Object>> decideCorrection(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,@PathVariable long id,@RequestBody Decision body){return ApiResponse.success(service.decideCorrection(user,id,body.approve(),body.comment()));}

    public record AdminPunchRequest(Long companyId, long userId, String punchTime, java.math.BigDecimal latitude,java.math.BigDecimal longitude,java.math.BigDecimal accuracy,String locationSource,String capturedAt,String remark,String punchType) {}
    public record Decision(boolean approve,String comment) {}
}
