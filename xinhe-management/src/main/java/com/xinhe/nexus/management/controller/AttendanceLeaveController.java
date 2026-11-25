package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.AttendanceLeaveService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/oa/attendance/leave-requests")
public class AttendanceLeaveController {
    private final AttendanceLeaveService service;

    public AttendanceLeaveController(AttendanceLeaveService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor,
            @RequestParam(defaultValue = "false") boolean review) {
        return ApiResponse.success(service.list(actor, review));
    }

    @GetMapping("/preview")
    public ApiResponse<AttendanceLeaveService.Preview> preview(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(service.preview(actor, startDate, endDate));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Long>> create(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor,
            @RequestPart("input") AttendanceLeaveService.Input input,
            @RequestPart(value = "attachment", required = false) MultipartFile file) {
        return ApiResponse.success(Map.of("id", service.create(actor, input, file)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor, @PathVariable long id) {
        return ApiResponse.success(service.detail(actor, id));
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<byte[]> attachment(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor, @PathVariable long id) {
        var attachment = service.download(actor, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(attachment.bytes());
    }

    @PostMapping("/{id}/decision")
    public ApiResponse<Void> review(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor, @PathVariable long id,
            @RequestBody AttendanceLeaveService.Decision decision) {
        service.review(actor, id, decision);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser actor, @PathVariable long id) {
        service.withdraw(actor, id);
        return ApiResponse.success(null);
    }
}
