package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.PersonalProfileService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/hr/profiles")
public class PersonalProfileController {
    private final PersonalProfileService service;
    public PersonalProfileController(PersonalProfileService service) { this.service = service; }

    @GetMapping("/me")
    public ApiResponse<Map<String,Object>> me(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) { return ApiResponse.success(service.myProfile(user)); }

    @PutMapping("/me/basic")
    public ApiResponse<Map<String,Object>> updateBasic(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody BasicUpdate body) { return ApiResponse.success(service.updateBasic(user, service.myProfile(user).get("id") instanceof Number n ? n.longValue() : 0, body.displayName(), body.phone(), body.email())); }

    @PostMapping("/me/change-requests")
    public ApiResponse<Map<String,Object>> submit(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody Map<String,Object> body) { return ApiResponse.success(service.submitChange(user, body)); }

    @GetMapping("/me/change-requests")
    public ApiResponse<List<Map<String,Object>>> mine(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) { return ApiResponse.success(service.mine(user)); }

    @GetMapping("/{id}")
    public ApiResponse<Map<String,Object>> detail(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.profile(user, id)); }

    @PutMapping("/{id}")
    public ApiResponse<Map<String,Object>> direct(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody Map<String,Object> body) { return ApiResponse.success(service.updateDirect(user, id, body)); }

    @GetMapping("/change-requests/pending")
    public ApiResponse<List<Map<String,Object>>> pending(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) { return ApiResponse.success(service.pending(user)); }

    @PostMapping("/change-requests/{id}/decide")
    public ApiResponse<Map<String,Object>> decide(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody Decision body) { return ApiResponse.success(service.decide(user, id, body.approve(), body.comment())); }

    public record BasicUpdate(String displayName, String phone, String email) {}
    public record Decision(boolean approve, String comment) {}
}
