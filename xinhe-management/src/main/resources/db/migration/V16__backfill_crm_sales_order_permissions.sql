-- 兼容历史岗位：迁移前已拥有完整CRM权限的岗位，应自动获得后来新增的销售订单权限。
INSERT IGNORE INTO sys_position_permission (
    company_id,
    position_id,
    permission_id,
    created_by
)
SELECT
    assigned.company_id,
    assigned.position_id,
    sales_permission.id,
    MIN(assigned.created_by)
FROM sys_position_permission assigned
JOIN sys_permission existing_permission
    ON existing_permission.id = assigned.permission_id
   AND existing_permission.module_code = 'CRM'
   AND existing_permission.permission_code <> 'crm:sales-orders'
   AND existing_permission.permission_code NOT LIKE 'crm:sales-order:%'
CROSS JOIN sys_permission sales_permission
WHERE sales_permission.permission_code IN (
    'crm:sales-orders',
    'crm:sales-order:view',
    'crm:sales-order:create',
    'crm:sales-order:edit',
    'crm:sales-order:withdraw',
    'crm:sales-order:approve'
)
GROUP BY assigned.company_id, assigned.position_id, sales_permission.id
HAVING COUNT(DISTINCT existing_permission.id) = (
    SELECT COUNT(*)
    FROM sys_permission legacy_crm_permission
    WHERE legacy_crm_permission.module_code = 'CRM'
      AND legacy_crm_permission.status = 'ENABLED'
      AND legacy_crm_permission.deleted_at IS NULL
      AND legacy_crm_permission.permission_code <> 'crm:sales-orders'
      AND legacy_crm_permission.permission_code NOT LIKE 'crm:sales-order:%'
);

-- 权限版本递增，通知依赖公司权限版本的客户端和缓存重新加载授权信息。
UPDATE sys_company company
SET auth_version = auth_version + 1
WHERE EXISTS (
    SELECT 1
    FROM sys_position position_record
    JOIN sys_position_permission assigned
        ON assigned.company_id = position_record.company_id
       AND assigned.position_id = position_record.id
    JOIN sys_permission permission_record
        ON permission_record.id = assigned.permission_id
       AND permission_record.permission_code = 'crm:sales-order:create'
    WHERE position_record.company_id = company.id
);

