package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.CrmSalesService;
import com.xinhe.nexus.management.service.CrmSalesService.OrderInput;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/crm")
public class CrmSalesController {
    private final CrmSalesService service;
    public CrmSalesController(CrmSalesService service){this.service=service;}
    @GetMapping("/sales-order-products") public ApiResponse<List<Map<String,Object>>> orderProducts(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.orderProducts(u));}
    @GetMapping("/sales-orders") public ApiResponse<List<Map<String,Object>>> orders(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam(required=false) String applicationNo,@RequestParam(required=false) String orderNo,@RequestParam(required=false) String customerName,@RequestParam(required=false) String salespersonName,@RequestParam(required=false) String approvalStatus){return ApiResponse.success(service.orders(u,applicationNo,orderNo,customerName,salespersonName,approvalStatus));}
    @GetMapping("/customers/{customerId}/sales-orders") public ApiResponse<List<Map<String,Object>>> customerOrders(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long customerId){return ApiResponse.success(service.customerOrders(u,customerId));}
    @GetMapping("/sales-orders/pending-approvals") public ApiResponse<List<Map<String,Object>>> pending(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u){return ApiResponse.success(service.pendingApprovals(u));}
    @GetMapping("/sales-orders/{id}") public ApiResponse<Map<String,Object>> order(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.order(u,id));}
    @PostMapping("/sales-orders") public ApiResponse<Map<String,Object>> create(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody OrderInput body){return ApiResponse.success(service.create(u,body));}
    @PutMapping("/sales-orders/{id}/resubmit") public ApiResponse<Map<String,Object>> resubmit(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody OrderInput body){return ApiResponse.success(service.resubmit(u,id,body));}
    @PostMapping("/sales-orders/{id}/withdraw") public ApiResponse<Void> withdraw(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.withdraw(u,id);return ApiResponse.success(null);}
    @PostMapping("/sales-orders/{id}/approve") public ApiResponse<Map<String,Object>> approve(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody Decision body){return ApiResponse.success(service.decide(u,id,true,body.opinion()));}
    @PostMapping("/sales-orders/{id}/reject") public ApiResponse<Map<String,Object>> reject(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody Decision body){return ApiResponse.success(service.decide(u,id,false,body.opinion()));}
    public record Decision(String opinion){}
}
