package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.AttendanceRuleService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/oa/attendance/rules")
public class AttendanceRuleController {
    private final AttendanceRuleService service;
    public AttendanceRuleController(AttendanceRuleService service){this.service=service;}
    @PutMapping("/{year}/time-ranges")
    public ApiResponse<Map<String,Object>> saveTimes(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,
            @PathVariable int year, @RequestBody AttendanceRuleService.TimeInput input) {
        return ApiResponse.success(service.saveTimes(u, year, input));
    }
    @GetMapping("/my-schedule")
    public ApiResponse<Map<String,Object>> schedule(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,
            @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.mySchedule(u, date));
    }
    @GetMapping("/{year}") public ApiResponse<Map<String,Object>> year(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year){return ApiResponse.success(service.getYear(u,year));}
    @PutMapping("/{year}/company-holidays") public ApiResponse<Map<String,Object>> company(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year,@RequestBody AttendanceRuleService.DatesInput input){return ApiResponse.success(service.saveCompany(u,year,input));}
    @GetMapping("/{year}/users") public ApiResponse<List<Map<String,Object>>> users(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year){return ApiResponse.success(service.users(u,year));}
    @GetMapping("/{year}/users/{userId}") public ApiResponse<Map<String,Object>> user(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year,@PathVariable long userId){return ApiResponse.success(service.getUser(u,year,userId));}
    @PutMapping("/{year}/users/{userId}/holidays") public ApiResponse<Map<String,Object>> saveUser(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year,@PathVariable long userId,@RequestBody AttendanceRuleService.DatesInput input){return ApiResponse.success(service.saveUser(u,year,userId,input));}
    @PostMapping("/{year}/publish") public ApiResponse<Map<String,Object>> publish(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable int year,@RequestBody(required=false) AttendanceRuleService.VersionInput input){return ApiResponse.success(service.publish(u,year,input));}
}
