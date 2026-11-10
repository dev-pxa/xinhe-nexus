package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.CrmService;
import com.xinhe.nexus.management.service.CrmService.CustomerInput;
import com.xinhe.nexus.management.service.CrmService.CategoryInput;
import com.xinhe.nexus.management.service.CrmService.FollowupInput;
import com.xinhe.nexus.management.service.CrmService.PlanInput;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** CRM API：只接受当前登录身份，不允许客户端指定公司或计划归属人。 */
@RestController
@RequestMapping("/api/crm")
public class CrmController {
    private final CrmService service;
    public CrmController(CrmService service) { this.service = service; }

    @GetMapping("/customers") public ApiResponse<List<Map<String,Object>>> customers(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam(required=false) String keyword,@RequestParam(required=false) String phone,@RequestParam(required=false) Long ownerUserId,@RequestParam(required=false) Long categoryId,@RequestParam(required=false) Boolean favorite){return ApiResponse.success(service.customers(u,keyword,phone,ownerUserId,categoryId,favorite));}
    @GetMapping("/categories") public ApiResponse<List<Map<String,Object>>> categories(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.categories(u));}
    @PostMapping("/categories") public ApiResponse<Map<String,Object>> createCategory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody CategoryInput body){return ApiResponse.success(Map.of("id",service.createCategory(u,body)));}
    @PutMapping("/customers/{id}/favorite") public ApiResponse<Void> favorite(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody Favorite body){service.setFavorite(u,id,body.favorite());return ApiResponse.success(null);}
    @GetMapping("/customers/{id}") public ApiResponse<Map<String,Object>> customer(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.customer(u,id));}
    @GetMapping("/eligible-users") public ApiResponse<List<Map<String,Object>>> eligibleUsers(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.eligibleUsers(u));}
    @GetMapping("/customers/mine") public ApiResponse<List<Map<String,Object>>> myCustomers(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.myCustomers(u));}
    @PostMapping("/customers") public ApiResponse<Map<String,Object>> createCustomer(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody CustomerInput body){return ApiResponse.success(service.saveCustomer(u,null,body));}
    @PutMapping("/customers/{id}") public ApiResponse<Map<String,Object>> updateCustomer(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody CustomerInput body){return ApiResponse.success(service.saveCustomer(u,id,body));}
    @GetMapping("/customers/{id}/followups") public ApiResponse<List<Map<String,Object>>> followups(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.followups(u,id));}
    @PostMapping("/customers/{id}/followups") public ApiResponse<Map<String,Object>> addFollowup(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody FollowupInput body){return ApiResponse.success(Map.of("id",service.addFollowup(u,id,body)));}
    @PostMapping("/followups/{id}/attachments") public ApiResponse<Map<String,Object>> uploadAttachment(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestParam MultipartFile file){return ApiResponse.success(Map.of("id",service.uploadFollowupAttachment(u,id,file)));}
    @GetMapping("/attachments/{id}") public ResponseEntity<Resource> downloadAttachment(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ResponseEntity.ok().contentType(MediaType.parseMediaType(service.followupAttachmentContentType(u,id))).header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=crm-attachment").body(service.followupAttachment(u,id));}
    @GetMapping("/daily-plans") public ApiResponse<Map<String,Object>> plans(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam int year,@RequestParam int month,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long ownerUserId){return ApiResponse.success(service.plans(u,year,month,page,pageSize,ownerUserId));}
    @GetMapping("/daily-plans/draft") public ApiResponse<List<Map<String,Object>>> dailyPlanDraft(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.dailyPlanDraftItems(u));}
    @PostMapping("/daily-plans") public ApiResponse<Map<String,Object>> createPlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody PlanInput body){return ApiResponse.success(Map.of("id",service.createPlan(u,body)));}
    @GetMapping("/daily-plans/{id}") public ApiResponse<Map<String,Object>> plan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.plan(u,id));}
    @PutMapping("/daily-plans/{id}") public ApiResponse<Void> updatePlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody PlanInput body){service.updatePlan(u,id,body);return ApiResponse.success(null);}
    @DeleteMapping("/daily-plans/{id}") public ApiResponse<Void> deletePlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.deletePlan(u,id);return ApiResponse.success(null);}
    @PutMapping("/daily-plan-items/{id}/completion") public ApiResponse<Void> complete(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody Completion body){service.completeItem(u,id,body.completed());return ApiResponse.success(null);}
    @GetMapping("/weekly-plans") public ApiResponse<Map<String,Object>> weeklyPlans(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam int year,@RequestParam int month,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) Long ownerUserId){return ApiResponse.success(service.weeklyPlans(u,year,month,page,pageSize,ownerUserId));}
    @GetMapping("/weekly-plans/draft") public ApiResponse<List<Map<String,Object>>> weeklyPlanDraft(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.weeklyPlanDraftItems(u));}
    @PostMapping("/weekly-plans") public ApiResponse<Map<String,Object>> createWeeklyPlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody PlanInput body){return ApiResponse.success(Map.of("id",service.createWeeklyPlan(u,body)));}
    @GetMapping("/weekly-plans/{id}") public ApiResponse<Map<String,Object>> weeklyPlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.weeklyPlan(u,id));}
    @PutMapping("/weekly-plans/{id}") public ApiResponse<Void> updateWeeklyPlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody PlanInput body){service.updateWeeklyPlan(u,id,body);return ApiResponse.success(null);}
    @DeleteMapping("/weekly-plans/{id}") public ApiResponse<Void> deleteWeeklyPlan(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.deleteWeeklyPlan(u,id);return ApiResponse.success(null);}
    @PutMapping("/weekly-plan-items/{id}/completion") public ApiResponse<Void> completeWeeklyItem(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody Completion body){service.completeWeeklyItem(u,id,body.completed());return ApiResponse.success(null);}
    public record Completion(boolean completed){}
    public record Favorite(boolean favorite){}
}
