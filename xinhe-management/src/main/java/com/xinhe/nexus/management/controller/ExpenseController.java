package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ExpenseService;
import com.xinhe.nexus.management.service.ExpenseService.SaveRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/oa/expenses")
public class ExpenseController {
    private final ExpenseService service;

    public ExpenseController(ExpenseService service) { this.service = service; }

    @GetMapping("/mine")
    public ApiResponse<List<Map<String, Object>>> mine(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) {
        return ApiResponse.success(service.listMine(user));
    }

    @GetMapping("/pending")
    public ApiResponse<List<Map<String, Object>>> pending(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) {
        return ApiResponse.success(service.listPending(user));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) {
        return ApiResponse.success(service.create(user));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) {
        return ApiResponse.success(service.detail(user, id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> save(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody SaveRequest body) {
        return ApiResponse.success(service.save(user, id, body));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Void> submit(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
                                    @PathVariable long id, @RequestBody VersionRequest body) {
        service.submit(user, id, body.version());
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
                                      @PathVariable long id, @RequestBody VersionRequest body) {
        service.withdraw(user, id, body.version());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) {
        service.delete(user, id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/review")
    public ApiResponse<Void> review(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody ReviewRequest body) {
        service.review(user, id, body.action(), body.opinion(), body.version());
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/attachments")
    public ApiResponse<Map<String, Object>> upload(
            @RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user,
            @PathVariable long id, @RequestParam long itemId, @RequestParam String kind, @RequestParam MultipartFile file,
            HttpServletRequest request) {
        long attachmentId = service.upload(user, id, itemId, kind, file);
        return ApiResponse.success(Map.of("id", attachmentId));
    }

    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> content(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long attachmentId) {
        Resource resource = service.resource(user, attachmentId);
        String contentType = service.contentType(user, attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=attachment")
                .body(resource);
    }

    public record VersionRequest(int version) {}
    public record ReviewRequest(String action, String opinion, int version) {}
}
