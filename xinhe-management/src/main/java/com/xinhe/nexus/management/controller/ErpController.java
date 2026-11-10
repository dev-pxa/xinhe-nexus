package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ErpService;
import com.xinhe.nexus.management.service.ErpService.OrderInput;
import com.xinhe.nexus.management.service.ErpService.ProductCategoryInput;
import com.xinhe.nexus.management.service.ErpService.ProductInput;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** ERP商品资料和销售订单审批接口。账套只从当前认证用户读取。 */
@RestController
@RequestMapping("/api/erp")
public class ErpController {
    private final ErpService service;
    public ErpController(ErpService service) { this.service = service; }

    @GetMapping("/products") public ApiResponse<List<Map<String,Object>>> products(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestParam(required=false) String productCode, @RequestParam(required=false) String productName, @RequestParam(required=false) String model, @RequestParam(required=false) String specification, @RequestParam(required=false) Long categoryId, @RequestParam(required=false) String status) { return ApiResponse.success(service.products(user, productCode, productName, model, specification, categoryId, status)); }
    @GetMapping("/products/{id}") public ApiResponse<Map<String,Object>> product(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.product(user, id)); }
    @PostMapping("/products") public ApiResponse<Map<String,Object>> createProduct(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody ProductInput body) { return ApiResponse.success(service.createProduct(user, body)); }
    @PutMapping("/products/{id}") public ApiResponse<Map<String,Object>> updateProduct(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody ProductInput body) { return ApiResponse.success(service.updateProduct(user, id, body)); }
    @PostMapping("/products/{id}/disable") public ApiResponse<Void> disableProduct(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { service.setProductStatus(user, id, false); return ApiResponse.success(null); }
    @PostMapping("/products/{id}/enable") public ApiResponse<Void> enableProduct(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { service.setProductStatus(user, id, true); return ApiResponse.success(null); }

    @GetMapping("/product-categories") public ApiResponse<List<Map<String,Object>>> productCategories(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestParam(required=false) Boolean enabledOnly) { return ApiResponse.success(service.productCategories(user, enabledOnly)); }
    @PostMapping("/product-categories") public ApiResponse<Map<String,Object>> createProductCategory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody ProductCategoryInput body) { return ApiResponse.success(service.createProductCategory(user, body)); }
    @PutMapping("/product-categories/{id}") public ApiResponse<Map<String,Object>> updateProductCategory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody ProductCategoryInput body) { return ApiResponse.success(service.updateProductCategory(user, id, body)); }
    @PostMapping("/product-categories/{id}/disable") public ApiResponse<Void> disableProductCategory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { service.setProductCategoryStatus(user, id, false); return ApiResponse.success(null); }
    @PostMapping("/product-categories/{id}/enable") public ApiResponse<Void> enableProductCategory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { service.setProductCategoryStatus(user, id, true); return ApiResponse.success(null); }

    @GetMapping("/sales-orders") public ApiResponse<List<Map<String,Object>>> orders(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) { return ApiResponse.success(service.orders(user, false)); }
    @GetMapping("/sales-orders/pending-approvals") public ApiResponse<List<Map<String,Object>>> pendingApprovals(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user) { return ApiResponse.success(service.orders(user, true)); }
    @GetMapping("/sales-orders/{id}") public ApiResponse<Map<String,Object>> orderDetail(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id) { return ApiResponse.success(service.orderDetail(user, id)); }
    @PostMapping("/sales-orders") public ApiResponse<Map<String,Object>> submitOrder(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @RequestBody OrderInput body) { return ApiResponse.success(service.submitOrder(user, body)); }
    @PutMapping("/sales-orders/{id}/resubmit") public ApiResponse<Map<String,Object>> resubmitOrder(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody OrderInput body) { return ApiResponse.success(service.resubmitOrder(user, id, body)); }
    @PostMapping("/sales-orders/{id}/approve") public ApiResponse<Map<String,Object>> approve(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody Approval body) { return ApiResponse.success(service.decideOrder(user, id, true, body.opinion())); }
    @PostMapping("/sales-orders/{id}/reject") public ApiResponse<Map<String,Object>> reject(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser user, @PathVariable long id, @RequestBody Approval body) { return ApiResponse.success(service.decideOrder(user, id, false, body.opinion())); }
    public record Approval(String opinion) {}
}
