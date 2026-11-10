#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-http://127.0.0.1:18081}"
DB_CONTAINER="${DB_CONTAINER:-xinhe-nexus-mysql}"
DB_NAME="${MYSQL_DATABASE:-xinhe_nexus}"
DB_PASSWORD="${MYSQL_ROOT_PASSWORD:-root}"
RUN_ID="$(date +%Y%m%d%H%M%S)"
ADMIN_COOKIE="/tmp/xinhe-api-admin-${RUN_ID}.cookie"
MANAGER_COOKIE="/tmp/xinhe-api-manager-${RUN_ID}.cookie"

command -v jq >/dev/null

mysql_exec() {
  docker exec "$DB_CONTAINER" mysql --default-character-set=utf8mb4 -uroot -p"$DB_PASSWORD" "$DB_NAME" -N -e "$1"
}

login() {
  local username="$1" cookie="$2"
  curl -fsS -c "$cookie" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${username}\",\"password\":\"admin\",\"remember\":false}" \
    "$API_BASE/api/auth/login" | jq -e '.code == 0' >/dev/null
}

call_api() {
  local cookie="$1" method="$2" path="$3" body="${4:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -b "$cookie" -H 'X-Tenant-Id: 1' -H 'Content-Type: application/json' -X "$method" -d "$body" "$API_BASE$path"
  else
    curl -fsS -b "$cookie" -H 'X-Tenant-Id: 1' -H 'Content-Type: application/json' -X "$method" "$API_BASE$path"
  fi
}

# 建立可重复运行的真实审批组织。测试经理复用管理员的BCrypt密码，但使用独立账号。
mysql_exec "
INSERT INTO sys_user(username,password_hash,display_name,user_type,status,must_change_password,created_by,updated_by)
SELECT 'sales_e2e_manager',(SELECT password_hash FROM sys_user WHERE username='admin'),'销售接口测试负责人','COMPANY_USER','ENABLED',0,2,2
WHERE NOT EXISTS(SELECT 1 FROM sys_user WHERE username='sales_e2e_manager');
SET @manager=(SELECT id FROM sys_user WHERE username='sales_e2e_manager');
INSERT IGNORE INTO sys_user_company(user_id,company_id,is_company_admin,status) VALUES(@manager,1,1,'ENABLED');
UPDATE sys_user_company SET is_company_admin=0,status='ENABLED',deleted_at=NULL WHERE user_id=@manager AND company_id=1;
SET @org=(SELECT id FROM sys_org_unit WHERE company_id=1 AND parent_id IS NULL AND deleted_at IS NULL LIMIT 1);
INSERT INTO sys_position(company_id,org_unit_id,position_code,position_name,status,created_by,updated_by)
SELECT 1,@org,'E2E-SALES','接口测试销售岗位','ENABLED',2,2 WHERE NOT EXISTS(SELECT 1 FROM sys_position WHERE company_id=1 AND position_code='E2E-SALES');
INSERT INTO sys_position(company_id,org_unit_id,position_code,position_name,status,created_by,updated_by)
SELECT 1,@org,'E2E-MANAGER','接口测试负责人岗位','ENABLED',2,2 WHERE NOT EXISTS(SELECT 1 FROM sys_position WHERE company_id=1 AND position_code='E2E-MANAGER');
SET @sales_pos=(SELECT id FROM sys_position WHERE company_id=1 AND position_code='E2E-SALES');
SET @manager_pos=(SELECT id FROM sys_position WHERE company_id=1 AND position_code='E2E-MANAGER');
INSERT IGNORE INTO sys_user_position(company_id,user_id,position_id,status,created_by,updated_by) VALUES(1,2,@sales_pos,'ENABLED',2,2);
INSERT IGNORE INTO sys_user_position(company_id,user_id,position_id,status,created_by,updated_by) VALUES(1,@manager,@manager_pos,'ENABLED',2,2);
INSERT IGNORE INTO sys_position_permission(company_id,position_id,permission_id,created_by)
SELECT 1,@manager_pos,id,2 FROM sys_permission WHERE permission_code IN ('crm:sales-order:view','crm:sales-order:approve');
INSERT IGNORE INTO sys_position_permission(company_id,position_id,permission_id,created_by)
SELECT 1,@manager_pos,id,2 FROM sys_permission WHERE permission_code IN ('crm:sales-order:create','crm:sales-order:edit');
UPDATE sys_org_unit SET manager_user_id=@manager,manager_position_id=@manager_pos WHERE id=@org;
INSERT INTO crm_customer(company_id,customer_no,customer_name,phone,address,source,owner_user_id,remark,created_by,updated_by)
SELECT 1,CONCAT('E2E-',${RUN_ID}),CONCAT('接口全流程客户-',${RUN_ID}),'13800000000','接口测试收货地址','其他',2,'自动化接口测试',2,2;
"

CUSTOMER_ID="$(mysql_exec "SELECT id FROM crm_customer WHERE company_id=1 AND customer_no='E2E-${RUN_ID}'")"
PRODUCT_IDS="$(mysql_exec "SELECT GROUP_CONCAT(id ORDER BY id) FROM (SELECT id FROM erp_product WHERE company_id=1 AND status='ENABLED' ORDER BY id LIMIT 3) p")"
IFS=',' read -r PRODUCT_1 PRODUCT_2 PRODUCT_3 <<< "$PRODUCT_IDS"
[[ -n "${PRODUCT_1:-}" && -n "${PRODUCT_2:-}" && -n "${PRODUCT_3:-}" ]]

login admin "$ADMIN_COOKIE"
login sales_e2e_manager "$MANAGER_COOKIE"

# 公司管理员的数据范围为全公司，列表查询必须能正常拼接 1=1 范围条件。
call_api "$ADMIN_COOKIE" GET /api/crm/sales-orders | jq -e '.code == 0 and (.data | type) == "array"' >/dev/null

# CRM开单可读取商品选项，但不需要也不应隐式授予ERP商品资料权限。
call_api "$MANAGER_COOKIE" GET /api/crm/sales-order-products | jq -e '.code == 0 and (.data | length) > 0' >/dev/null
ERP_PRODUCT_HTTP="$(curl -sS -o /tmp/xinhe-erp-product-denied-${RUN_ID}.json -w '%{http_code}' -b "$MANAGER_COOKIE" -H 'X-Tenant-Id: 1' "$API_BASE/api/erp/products")"
[[ "$ERP_PRODUCT_HTTP" == "403" ]]

WAREHOUSE_A_JSON="$(call_api "$ADMIN_COOKIE" POST /api/erp/warehouses "{\"warehouseName\":\"自动化仓库A-${RUN_ID}\",\"address\":\"A区\",\"remark\":\"接口测试\"}")"
WAREHOUSE_B_JSON="$(call_api "$ADMIN_COOKIE" POST /api/erp/warehouses "{\"warehouseName\":\"自动化仓库B-${RUN_ID}\",\"address\":\"B区\",\"remark\":\"接口测试\"}")"
WAREHOUSE_A="$(jq -er '.data.id' <<< "$WAREHOUSE_A_JSON")"
WAREHOUSE_B="$(jq -er '.data.id' <<< "$WAREHOUSE_B_JSON")"
call_api "$ADMIN_COOKIE" PUT "/api/erp/warehouses/${WAREHOUSE_A}" "{\"warehouseName\":\"自动化仓库A-${RUN_ID}\",\"address\":\"A区更新地址\",\"remark\":\"CRUD更新测试\",\"version\":0}" | jq -e '.code == 0 and .data.address == "A区更新地址"' >/dev/null
call_api "$ADMIN_COOKIE" POST "/api/erp/warehouses/${WAREHOUSE_B}/disable" | jq -e '.code == 0' >/dev/null
call_api "$ADMIN_COOKIE" POST "/api/erp/warehouses/${WAREHOUSE_B}/enable" | jq -e '.code == 0' >/dev/null
WAREHOUSE_DELETE="$(call_api "$ADMIN_COOKIE" POST /api/erp/warehouses "{\"warehouseName\":\"自动化待删除仓库-${RUN_ID}\"}" | jq -er '.data.id')"
call_api "$ADMIN_COOKIE" DELETE "/api/erp/warehouses/${WAREHOUSE_DELETE}" | jq -e '.code == 0' >/dev/null

for product in "$PRODUCT_1" "$PRODUCT_2"; do
  call_api "$ADMIN_COOKIE" POST /api/erp/inventory/adjust "{\"warehouseId\":${WAREHOUSE_A},\"productId\":${product},\"quantity\":100,\"reason\":\"自动化测试期初库存\"}" | jq -e '.code == 0' >/dev/null
done
call_api "$ADMIN_COOKIE" POST /api/erp/inventory/adjust "{\"warehouseId\":${WAREHOUSE_B},\"productId\":${PRODUCT_3},\"quantity\":100,\"reason\":\"自动化测试期初库存\"}" | jq -e '.code == 0' >/dev/null

ORDER_JSON="$(call_api "$ADMIN_COOKIE" POST /api/crm/sales-orders "{\"customerId\":${CUSTOMER_ID},\"phone\":\"13900000000\",\"shippingAddress\":\"订单可编辑地址\",\"remark\":\"完整链路自动化测试\",\"lines\":[{\"productId\":${PRODUCT_1},\"quantity\":2},{\"productId\":${PRODUCT_2},\"quantity\":3},{\"productId\":${PRODUCT_3},\"quantity\":4}]}")"
CRM_ORDER_ID="$(jq -er '.data.id' <<< "$ORDER_JSON")"
CRM_APPLICATION_NO="$(jq -er '.data.applicationNo' <<< "$ORDER_JSON")"

TEAM_ORDER_COUNT="$(call_api "$MANAGER_COOKIE" GET "/api/crm/sales-orders?applicationNo=${CRM_APPLICATION_NO}" | jq --argjson id "$CRM_ORDER_ID" '[.data[]|select(.id==$id)]|length')"
[[ "$TEAM_ORDER_COUNT" == "1" ]]
call_api "$MANAGER_COOKIE" GET "/api/crm/sales-orders/${CRM_ORDER_ID}" | jq --argjson id "$CRM_ORDER_ID" -e '.code == 0 and .data.id == $id' >/dev/null

PENDING_COUNT="$(call_api "$MANAGER_COOKIE" GET /api/crm/sales-orders/pending-approvals | jq --argjson id "$CRM_ORDER_ID" '[.data[]|select(.id==$id)]|length')"
[[ "$PENDING_COUNT" == "1" ]]

APPROVE_JSON="$(call_api "$MANAGER_COOKIE" POST "/api/crm/sales-orders/${CRM_ORDER_ID}/approve" '{"opinion":"接口自动化审核通过"}')"
ERP_ORDER_ID="$(jq -er '.data.erpOrderId' <<< "$APPROVE_JSON")"
ORDER_NO="$(jq -er '.data.orderNo' <<< "$APPROVE_JSON")"

ERP_LIST="$(call_api "$ADMIN_COOKIE" GET "/api/erp/fulfillment-orders?orderNo=${ORDER_NO}")"
[[ "$(jq --argjson id "$ERP_ORDER_ID" '[.data[]|select(.id==$id)]|length' <<< "$ERP_LIST")" == "1" ]]
DETAIL="$(call_api "$ADMIN_COOKIE" GET "/api/erp/fulfillment-orders/${ERP_ORDER_ID}")"
LINE_1="$(jq -er '.data.lines[0].id' <<< "$DETAIL")"
LINE_2="$(jq -er '.data.lines[1].id' <<< "$DETAIL")"
LINE_3="$(jq -er '.data.lines[2].id' <<< "$DETAIL")"

# 严格校验遗漏商品会被拒绝。
HTTP_CODE="$(curl -sS -o /tmp/xinhe-invalid-dispatch-${RUN_ID}.json -w '%{http_code}' -b "$ADMIN_COOKIE" -H 'X-Tenant-Id: 1' -H 'Content-Type: application/json' -X POST -d "{\"groups\":[{\"warehouseId\":${WAREHOUSE_A},\"lineIds\":[${LINE_1}]}]}" "$API_BASE/api/erp/fulfillment-orders/${ERP_ORDER_ID}/dispatch")"
[[ "$HTTP_CODE" == "400" ]]

DISPATCH_JSON="$(call_api "$ADMIN_COOKIE" POST "/api/erp/fulfillment-orders/${ERP_ORDER_ID}/dispatch" "{\"groups\":[{\"warehouseId\":${WAREHOUSE_A},\"lineIds\":[${LINE_1},${LINE_2}],\"carrier\":\"仓库A配送\",\"trackingNo\":\"A-${RUN_ID}\"},{\"warehouseId\":${WAREHOUSE_B},\"lineIds\":[${LINE_3}],\"carrier\":\"仓库B配送\",\"trackingNo\":\"B-${RUN_ID}\"}]}")"
[[ "$(jq '.data|length' <<< "$DISPATCH_JSON")" == "2" ]]
[[ "$(jq -r '[.data[].dispatchNo]|unique|length' <<< "$DISPATCH_JSON")" == "2" ]]

call_api "$ADMIN_COOKIE" POST "/api/erp/fulfillment-orders/${ERP_ORDER_ID}/sign" '{"signedByName":"测试签收人","remark":"自动化签收"}' | jq -e '.code == 0' >/dev/null
call_api "$ADMIN_COOKIE" POST "/api/erp/fulfillment-orders/${ERP_ORDER_ID}/complete" | jq -e '.code == 0' >/dev/null
FINAL_STATUS="$(call_api "$ADMIN_COOKIE" GET "/api/erp/fulfillment-orders/${ERP_ORDER_ID}" | jq -er '.data.fulfillmentStatus')"
[[ "$FINAL_STATUS" == "COMPLETED" ]]

RETURN_JSON="$(call_api "$ADMIN_COOKIE" POST /api/erp/sales-returns "{\"orderId\":${ERP_ORDER_ID},\"reason\":\"自动化退货测试\",\"lines\":[{\"orderLineId\":${LINE_1},\"quantity\":1}]}")"
RETURN_ID="$(jq -er '.data.id' <<< "$RETURN_JSON")"
call_api "$ADMIN_COOKIE" POST "/api/erp/sales-returns/${RETURN_ID}/approve" '{"opinion":"退货审核通过"}' | jq -e '.code == 0' >/dev/null
call_api "$ADMIN_COOKIE" POST "/api/erp/sales-returns/${RETURN_ID}/receive" | jq -e '.code == 0' >/dev/null
[[ "$(call_api "$ADMIN_COOKIE" GET "/api/erp/sales-returns/${RETURN_ID}" | jq -er '.data.status')" == "RECEIVED" ]]
[[ "$(call_api "$ADMIN_COOKIE" GET "/api/erp/fulfillment-orders/${ERP_ORDER_ID}" | jq -er '.data.fulfillmentStatus')" == "RETURNED" ]]
REPORT_RETURN_QTY="$(call_api "$ADMIN_COOKIE" GET '/api/erp/sales-reports?dimension=product' | jq --argjson id "$PRODUCT_1" -r '[.data[]|select(.dimensionId==$id)|.returnQuantity]|add // 0')"
[[ "$REPORT_RETURN_QTY" -ge 1 ]]

QTY_A1="$(call_api "$ADMIN_COOKIE" GET "/api/erp/inventory?warehouseId=${WAREHOUSE_A}&productCode=PRD" | jq --argjson id "$PRODUCT_1" -r '.data[]|select(.productId==$id)|.quantity')"
QTY_A2="$(call_api "$ADMIN_COOKIE" GET "/api/erp/inventory?warehouseId=${WAREHOUSE_A}&productCode=PRD" | jq --argjson id "$PRODUCT_2" -r '.data[]|select(.productId==$id)|.quantity')"
QTY_B3="$(call_api "$ADMIN_COOKIE" GET "/api/erp/inventory?warehouseId=${WAREHOUSE_B}&productCode=PRD" | jq --argjson id "$PRODUCT_3" -r '.data[]|select(.productId==$id)|.quantity')"
[[ "$QTY_A1" == "99" && "$QTY_A2" == "97" && "$QTY_B3" == "96" ]]

jq -n --arg runId "$RUN_ID" --arg orderNo "$ORDER_NO" --argjson crmOrderId "$CRM_ORDER_ID" --argjson erpOrderId "$ERP_ORDER_ID" --argjson warehouseA "$WAREHOUSE_A" --argjson warehouseB "$WAREHOUSE_B" --argjson returnId "$RETURN_ID" '{success:true,runId:$runId,crmOrderId:$crmOrderId,erpOrderId:$erpOrderId,returnId:$returnId,orderNo:$orderNo,warehouses:[$warehouseA,$warehouseB],assertions:["CRM权限独立读取开单商品","ERP商品权限仍隔离","仓库新增修改启停删除","库存调整","CRM订单创建","负责人查看下级销售订单","负责人待办","审批同步ERP","正式订单查询与详情","遗漏商品拒绝","多仓生成两张出库单","库存分别扣减","签收","完成","退货申请审核收货","退货库存回补","销售统计"]}'
