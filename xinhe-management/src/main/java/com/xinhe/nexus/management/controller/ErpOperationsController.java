package com.xinhe.nexus.management.controller;

import com.xinhe.nexus.common.api.ApiResponse;
import com.xinhe.nexus.management.auth.AuthConstants;
import com.xinhe.nexus.management.auth.ManagementUser;
import com.xinhe.nexus.management.service.ErpOperationsService;
import com.xinhe.nexus.management.service.ErpOperationsService.*;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/erp")
public class ErpOperationsController {
    private final ErpOperationsService service;
    public ErpOperationsController(ErpOperationsService service){this.service=service;}
    @GetMapping("/warehouses") public ApiResponse<List<Map<String,Object>>> warehouses(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam(required=false) Boolean enabledOnly){return ApiResponse.success(service.warehouses(u,enabledOnly));}
    @PostMapping("/warehouses") public ApiResponse<Map<String,Object>> createWarehouse(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody WarehouseInput body){return ApiResponse.success(service.createWarehouse(u,body));}
    @PutMapping("/warehouses/{id}") public ApiResponse<Map<String,Object>> updateWarehouse(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody WarehouseInput body){return ApiResponse.success(service.updateWarehouse(u,id,body));}
    @PostMapping("/warehouses/{id}/enable") public ApiResponse<Void> enableWarehouse(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.setWarehouseStatus(u,id,true);return ApiResponse.success(null);}
    @PostMapping("/warehouses/{id}/disable") public ApiResponse<Void> disableWarehouse(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.setWarehouseStatus(u,id,false);return ApiResponse.success(null);}
    @DeleteMapping("/warehouses/{id}") public ApiResponse<Void> deleteWarehouse(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.deleteWarehouse(u,id);return ApiResponse.success(null);}
    @GetMapping("/inventory") public ApiResponse<List<Map<String,Object>>> inventory(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam(required=false) Long warehouseId,@RequestParam(required=false) String productCode,@RequestParam(required=false) String productName){return ApiResponse.success(service.inventory(u,warehouseId,productCode,productName));}
    @PostMapping("/inventory/adjust") public ApiResponse<Map<String,Object>> adjust(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestBody InventoryAdjustment body){return ApiResponse.success(service.adjustInventory(u,body));}
    @GetMapping("/fulfillment-orders") public ApiResponse<List<Map<String,Object>>> orders(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@RequestParam(required=false) String orderNo,@RequestParam(required=false) String customerName,@RequestParam(required=false) Long salespersonUserId,@RequestParam(required=false) String status){return ApiResponse.success(service.salesOrders(u,orderNo,customerName,salespersonUserId,status));}
    @GetMapping("/fulfillment-orders/{id}") public ApiResponse<Map<String,Object>> order(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){return ApiResponse.success(service.salesOrder(u,id));}
    @PostMapping("/fulfillment-orders/{id}/dispatch") public ApiResponse<List<Map<String,Object>>> dispatch(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody DispatchInput body){return ApiResponse.success(service.dispatch(u,id,body));}
    @PostMapping("/fulfillment-orders/{id}/sign") public ApiResponse<Void> sign(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id,@RequestBody SignInput body){service.sign(u,id,body);return ApiResponse.success(null);}
    @PostMapping("/fulfillment-orders/{id}/complete") public ApiResponse<Void> complete(@RequestAttribute(AuthConstants.CURRENT_USER_ATTRIBUTE) ManagementUser u,@PathVariable long id){service.complete(u,id);return ApiResponse.success(null);}
}
